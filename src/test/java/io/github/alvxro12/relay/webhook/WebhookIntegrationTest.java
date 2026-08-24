package io.github.alvxro12.relay.webhook;

import io.github.alvxro12.relay.messaging.RabbitConfig;
import io.github.alvxro12.relay.payment.Payment;
import io.github.alvxro12.relay.payment.PaymentRepository;
import io.github.alvxro12.relay.payment.PaymentResult;
import io.github.alvxro12.relay.payment.PaymentStatus;
import io.github.alvxro12.relay.payment.dto.CreatePaymentRequest;
import io.github.alvxro12.relay.payment.service.PaymentService;
import io.github.alvxro12.relay.provider.ChargeStatus;
import io.github.alvxro12.relay.provider.FakePaymentProvider;
import io.github.alvxro12.relay.webhook.service.WebhookSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Se construye MockMvc a mano en vez de usar webEnvironment = RANDOM_PORT a
 * propósito: RANDOM_PORT crearía un segundo contexto de Spring y sus
 * @RabbitListener competirían con los del contexto de los tests de payment por
 * las mismas colas, con un FakePaymentProvider distinto en cada uno. Con
 * @SpringBootTest pelado todos los tests comparten un único contexto.
 */
@SpringBootTest
class WebhookIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL = Duration.ofMillis(100);

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private WebhookSignatureVerifier signatureVerifier;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private FakePaymentProvider fakePaymentProvider;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // .apply(springSecurity()) para que el webhook se pruebe contra la cadena real:
        // sin esto MockMvc saltea los filtros de Spring Security y el test no diria nada
        // sobre si el endpoint quedo publico o cerrado.
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        // Bean singleton compartido: sin reset, lo que fuerza un test se filtra al siguiente.
        fakePaymentProvider.forceNextResult(ChargeStatus.SUCCESS);
        fakePaymentProvider.forceNextTransactionId(null);
        fakePaymentProvider.onNextCharge(null);

        rabbitAdmin.purgeQueue(RabbitConfig.WEBHOOK_DLQ, false);
    }

    @Test
    void invalidSignature_isRejectedWithoutTouchingDbOrQueue() throws Exception {
        String eventId = eventId("badsig");
        String body = payload(eventId, "payment.succeeded", "fake_txn_" + UUID.randomUUID());

        // Firma calculada con otro secreto: HMAC bien formado pero que no valida.
        String forgedSignature = "a".repeat(64);

        mockMvc.perform(post("/webhooks/provider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookSignatureVerifier.SIGNATURE_HEADER, forgedSignature)
                        .content(body))
                .andExpect(status().isUnauthorized());

        // Sin header de firma tampoco pasa (y no debe caer en el 422 de header faltante).
        mockMvc.perform(post("/webhooks/provider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        // Nada en la DB. Como la publicación al broker cuelga del afterCommit de
        // este mismo insert, que la fila no exista prueba que tampoco se encoló nada.
        assertThat(webhookEventRepository.findByProviderEventId(eventId)).isEmpty();
        assertThat(countWebhookEventRows(eventId)).isZero();
        assertThat(depthOf(RabbitConfig.WEBHOOK_DLQ)).isZero();
    }

    @Test
    void validSignature_persistsEventAndConsumerUpdatesPayment() throws Exception {
        String providerTransactionId = "fake_txn_" + UUID.randomUUID();
        UUID paymentId = chargeAcceptedPayment(providerTransactionId, "wh-valid");

        String eventId = eventId("valid");
        String body = payload(eventId, "payment.succeeded", providerTransactionId);

        // Respuesta rápida: el 200 sale antes de que se valide nada del negocio.
        mockMvc.perform(signedPost(body)).andExpect(status().isOk());

        // El evento se persiste de forma síncrona, antes de responder.
        WebhookEvent stored = webhookEventRepository.findByProviderEventId(eventId).orElseThrow();
        assertThat(stored.getRawPayload()).isEqualTo(body);
        assertThat(stored.getReceivedAt()).isNotNull();

        // El consumer corre asincrónico: esperamos el estado real en la DB.
        await().atMost(TIMEOUT).pollInterval(POLL).untilAsserted(() -> {
            Payment payment = paymentRepository.findById(paymentId).orElseThrow();
            assertThat(payment.getStatus())
                    .as("el webhook resuelve el pago que el proveedor había aceptado")
                    .isEqualTo(PaymentStatus.SUCCEEDED);

            WebhookEvent processed = webhookEventRepository.findById(stored.getId()).orElseThrow();
            assertThat(processed.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
            assertThat(processed.getProcessedAt()).isNotNull();
        });

        assertThat(depthOf(RabbitConfig.WEBHOOK_DLQ)).isZero();
    }

    @Test
    void duplicateWebhook_isProcessedExactlyOnce() throws Exception {
        String providerTransactionId = "fake_txn_" + UUID.randomUUID();
        UUID paymentId = chargeAcceptedPayment(providerTransactionId, "wh-dup");
        long versionBefore = paymentRepository.findById(paymentId).orElseThrow().getVersion();

        String eventId = eventId("dup");
        String body = payload(eventId, "payment.succeeded", providerTransactionId);

        mockMvc.perform(signedPost(body)).andExpect(status().isOk());

        await().atMost(TIMEOUT).pollInterval(POLL).untilAsserted(() ->
                assertThat(webhookEventRepository.findByProviderEventId(eventId).orElseThrow().getStatus())
                        .isEqualTo(WebhookEventStatus.PROCESSED));

        WebhookEvent afterFirst = webhookEventRepository.findByProviderEventId(eventId).orElseThrow();
        Instant processedAtAfterFirst = afterFirst.getProcessedAt();
        long versionAfterFirst = paymentRepository.findById(paymentId).orElseThrow().getVersion();

        // Reentrega del proveedor: mismo cuerpo, misma firma. También responde 200,
        // porque cualquier cosa que no sea 2xx lo haría reintentar de nuevo.
        mockMvc.perform(signedPost(body)).andExpect(status().isOk());

        // Un 200 no prueba nada por sí solo: lo que prueba el procesamiento único
        // es el conteo real de filas y que ni el evento ni el pago se volvieron a tocar.
        assertThat(countWebhookEventRows(eventId))
                .as("la unique constraint sobre provider_event_id impide la segunda fila")
                .isEqualTo(1);

        await().during(Duration.ofSeconds(2)).atMost(TIMEOUT).untilAsserted(() -> {
            WebhookEvent afterSecond = webhookEventRepository.findByProviderEventId(eventId).orElseThrow();
            assertThat(afterSecond.getProcessedAt())
                    .as("processed_at intacto: el consumer no volvió a correr")
                    .isEqualTo(processedAtAfterFirst);

            Payment payment = paymentRepository.findById(paymentId).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(payment.getVersion())
                    .as("una sola escritura sobre el pago: version subió exactamente una vez")
                    .isEqualTo(versionAfterFirst)
                    .isEqualTo(versionBefore + 1);
        });

        assertThat(depthOf(RabbitConfig.WEBHOOK_DLQ)).isZero();
    }

    @Test
    void webhookForUnknownPayment_endsInDlqAndMarkedFailed() throws Exception {
        String orphanTransactionId = "fake_txn_orphan_" + UUID.randomUUID();
        String eventId = eventId("orphan");
        String body = payload(eventId, "payment.succeeded", orphanTransactionId);

        mockMvc.perform(signedPost(body)).andExpect(status().isOk());

        // Los reintentos con backoff cubren la carrera legítima (webhook que llega
        // antes de que charge commitee el providerTransactionId). Acá nunca va a
        // aparecer, así que se agotan y termina acotado: DLQ + FAILED, no bucle infinito.
        await().atMost(TIMEOUT).pollInterval(POLL).untilAsserted(() -> {
            WebhookEvent failed = webhookEventRepository.findByProviderEventId(eventId).orElseThrow();
            assertThat(failed.getStatus()).isEqualTo(WebhookEventStatus.FAILED);
            assertThat(failed.getProcessedAt()).isNotNull();
        });

        await().atMost(TIMEOUT).pollInterval(POLL).untilAsserted(() ->
                assertThat(depthOf(RabbitConfig.WEBHOOK_DLQ)).isEqualTo(1));

        // Y deja de rebotar: la cola principal queda vacía y estable.
        await().during(Duration.ofSeconds(3)).atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(depthOf(RabbitConfig.WEBHOOK_QUEUE)).isZero();
            assertThat(depthOf(RabbitConfig.WEBHOOK_DLQ)).isEqualTo(1);
        });
    }

    @Test
    void webhookArrivingMidCharge_doesNotClobberTheChargeResult() throws Exception {
        String providerTransactionId = "fake_txn_race_" + UUID.randomUUID();
        CountDownLatch chargeInFlight = new CountDownLatch(1);
        CountDownLatch releaseCharge = new CountDownLatch(1);

        fakePaymentProvider.forceNextResult(ChargeStatus.SUCCESS);
        fakePaymentProvider.forceNextTransactionId(providerTransactionId);
        fakePaymentProvider.onNextCharge(() -> {
            chargeInFlight.countDown();
            try {
                releaseCharge.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        UUID merchantId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentResult result = paymentService.createPayment(
                merchantId, idempotencyKey, new CreatePaymentRequest(1000L, "USD", "wh-race"));
        UUID paymentId = result.payment().getId();

        // El consumer de charge ya dejó el pago en PROCESSING y está dentro del provider.
        assertThat(chargeInFlight.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .as("el consumer de charge tiene que haber entrado al provider")
                .isTrue();

        // El webhook entra justo en el medio, intentando dejar el pago en FAILED.
        String eventId = eventId("race");
        String body = payload(eventId, "payment.failed", providerTransactionId);
        mockMvc.perform(signedPost(body)).andExpect(status().isOk());

        releaseCharge.countDown();

        await().atMost(TIMEOUT).pollInterval(POLL).untilAsserted(() -> {
            // Gana el resultado real del cobro: el webhook no revierte un estado terminal.
            Payment payment = paymentRepository.findById(paymentId).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(payment.getProviderTransactionId()).isEqualTo(providerTransactionId);

            // Y el evento igual se cierra como PROCESSED, para que no se reintente.
            WebhookEvent event = webhookEventRepository.findByProviderEventId(eventId).orElseThrow();
            assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
        });

        assertThat(depthOf(RabbitConfig.WEBHOOK_DLQ)).isZero();
    }

    // --- helpers ---

    private org.springframework.test.web.servlet.RequestBuilder signedPost(String body) {
        return post("/webhooks/provider")
                .contentType(MediaType.APPLICATION_JSON)
                .header(WebhookSignatureVerifier.SIGNATURE_HEADER, signatureVerifier.sign(body))
                .content(body);
    }

    private static String payload(String eventId, String type, String providerTransactionId) {
        return "{\"id\":\"" + eventId + "\",\"type\":\"" + type
                + "\",\"data\":{\"providerTransactionId\":\"" + providerTransactionId + "\"}}";
    }

    private static String eventId(String caseName) {
        return "evt_" + caseName + "_" + UUID.randomUUID();
    }

    /**
     * Deja un pago en AWAITING_CONFIRMATION recorriendo el flujo real: createPayment,
     * consumer de charge, provider devolviendo ACCEPTED con id de transacción. Es
     * exactamente el pago que un webhook viene a cerrar, y sale del sistema en vez de
     * sembrarse a mano.
     */
    private UUID chargeAcceptedPayment(String providerTransactionId, String reference) {
        fakePaymentProvider.forceNextResult(ChargeStatus.ACCEPTED);
        fakePaymentProvider.forceNextTransactionId(providerTransactionId);

        PaymentResult result = paymentService.createPayment(
                UUID.randomUUID(), UUID.randomUUID().toString(),
                new CreatePaymentRequest(1000L, "USD", reference));
        UUID paymentId = result.payment().getId();

        await().atMost(TIMEOUT).pollInterval(POLL).untilAsserted(() ->
                assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                        .isEqualTo(PaymentStatus.AWAITING_CONFIRMATION));

        return paymentId;
    }

    private int countWebhookEventRows(String providerEventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM webhook_events WHERE provider_event_id = ?",
                Integer.class, providerEventId);
        return count == null ? 0 : count;
    }

    private long depthOf(String queue) {
        QueueInformation info = rabbitAdmin.getQueueInfo(queue);
        return info == null ? 0L : info.getMessageCount();
    }
}
