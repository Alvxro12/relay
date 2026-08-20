package io.github.alvxro12.relay.payment;

import io.github.alvxro12.relay.payment.dto.CreatePaymentRequest;
import io.github.alvxro12.relay.payment.service.PaymentService;
import io.github.alvxro12.relay.provider.ChargeStatus;
import io.github.alvxro12.relay.provider.FakePaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
class PaymentChargeServiceIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL = Duration.ofMillis(100);

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentEventPublisher paymentEventPublisher;

    @Autowired
    private FakePaymentProvider fakePaymentProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetFakeProvider() {
        // Bean singleton: sin este reset, un test anterior podría dejar forzado un
        // resultado, un id de transacción, un hook o un conteo que contamine el siguiente.
        fakePaymentProvider.forceNextResult(ChargeStatus.SUCCESS);
        fakePaymentProvider.forceNextTransactionId(null);
        fakePaymentProvider.onNextCharge(null);
        fakePaymentProvider.resetChargeCount();
    }

    @Test
    void success_marksPaymentAsSucceeded() {
        assertFinalStatus(ChargeStatus.SUCCESS, PaymentStatus.SUCCEEDED);
    }

    /**
     * ACCEPTED no es un resultado: es el proveedor haciéndose cargo del cobro. El pago
     * no puede quedar en PENDING —volvería a ser candidato a cobrarse— ni en un estado
     * terminal, porque todavía no se sabe cómo termina.
     */
    @Test
    void accepted_marksPaymentAsAwaitingConfirmation() {
        assertFinalStatus(ChargeStatus.ACCEPTED, PaymentStatus.AWAITING_CONFIRMATION);
    }

    @Test
    void declined_marksPaymentAsFailed() {
        assertFinalStatus(ChargeStatus.DECLINED, PaymentStatus.FAILED);
    }

    @Test
    void serverError_marksPaymentAsUnknown() {
        assertFinalStatus(ChargeStatus.SERVER_ERROR, PaymentStatus.UNKNOWN);
    }

    @Test
    void timeout_marksPaymentAsUnknown() {
        assertFinalStatus(ChargeStatus.TIMEOUT, PaymentStatus.UNKNOWN);
    }

    /**
     * Reentrega del mismo evento: RabbitMQ garantiza at-least-once, así que el mismo
     * ChargeRequestedEvent puede llegar dos veces sin que nada haya fallado. Lo que no
     * puede pasar es que el proveedor se llame dos veces —eso es cobrarle dos veces al
     * mismo cliente—, y lo que lo impide es el PROCESSING que commitea el claim: la
     * segunda entrega relee un estado que ya no es PENDING y se va sin cobrar.
     */
    @Test
    void redeliveredChargeEvent_chargesProviderExactlyOnce() {
        String providerTransactionId = "fake_txn_redelivery_" + UUID.randomUUID();
        fakePaymentProvider.forceNextResult(ChargeStatus.SUCCESS);
        fakePaymentProvider.forceNextTransactionId(providerTransactionId);

        UUID merchantId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentResult result = paymentService.createPayment(
                merchantId, idempotencyKey, new CreatePaymentRequest(1000L, "USD", "redelivery"));
        Payment created = result.payment();
        UUID paymentId = created.getId();

        // La segunda entrega, publicada a mano: el broker reentregando el mismo mensaje
        // se ve exactamente así desde el lado del consumer.
        paymentEventPublisher.publishChargeRequested(new ChargeRequestedEvent(
                paymentId, merchantId, created.getAmount(), created.getCurrency()));

        await().atMost(TIMEOUT).pollInterval(POLL).untilAsserted(() ->
                assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                        .isEqualTo(PaymentStatus.SUCCEEDED));

        // El estado final no alcanza como prueba: se ve igual con uno o con dos cobros.
        // Se sostiene la aserción en el tiempo para darle margen a la segunda entrega a
        // que se procese y, si fuera a cobrar de nuevo, se note.
        await().during(Duration.ofSeconds(2)).atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(fakePaymentProvider.chargeCount())
                    .as("una sola llamada al proveedor pese a las dos entregas")
                    .isEqualTo(1);

            Payment payment = paymentRepository.findById(paymentId).orElseThrow();
            assertThat(payment.getStatus())
                    .as("el pago queda en el resultado del primer cobro")
                    .isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(payment.getProviderTransactionId()).isEqualTo(providerTransactionId);
            assertThat(payment.getVersion())
                    .as("exactamente dos escrituras: el claim a PROCESSING y el resultado")
                    .isEqualTo(2L);
        });
    }

    /**
     * El PROCESSING tiene que estar commiteado antes de que arranque el cobro, no solo
     * flusheado dentro de una transacción que nadie más ve. Ese es el punto del
     * claim-then-call: mientras el cobro está en vuelo, cualquier observador externo
     * —otro consumer, un endpoint de consulta, un operador mirando la tabla— lee
     * PROCESSING y sabe que ese pago ya está tomado.
     */
    @Test
    void processingIsVisibleToOtherReadersWhileTheChargeIsInFlight() throws Exception {
        CountDownLatch chargeInFlight = new CountDownLatch(1);
        CountDownLatch releaseCharge = new CountDownLatch(1);

        fakePaymentProvider.forceNextResult(ChargeStatus.SUCCESS);
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
                merchantId, idempotencyKey, new CreatePaymentRequest(1000L, "USD", "processing-visible"));
        UUID paymentId = result.payment().getId();

        assertThat(chargeInFlight.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .as("el consumer de charge tiene que haber entrado al provider")
                .isTrue();

        // Lectura cruda contra la DB, desde otro hilo y otra conexión: sin JPA de por
        // medio, lo que se lee es lo que está commiteado y no lo que tenga cacheado el
        // persistence context de nadie.
        assertThat(readStatusFromDb(paymentId))
                .as("el PROCESSING del claim está commiteado y visible durante el cobro")
                .isEqualTo(PaymentStatus.PROCESSING.name());

        releaseCharge.countDown();

        await().atMost(TIMEOUT).pollInterval(POLL).untilAsserted(() ->
                assertThat(readStatusFromDb(paymentId)).isEqualTo(PaymentStatus.SUCCEEDED.name()));
    }

    private void assertFinalStatus(ChargeStatus providerResult, PaymentStatus expectedStatus) {
        fakePaymentProvider.forceNextResult(providerResult);

        UUID merchantId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        CreatePaymentRequest request = new CreatePaymentRequest(1000L, "USD", "order-" + idempotencyKey);

        PaymentResult result = paymentService.createPayment(merchantId, idempotencyKey, request);
        UUID paymentId = result.payment().getId();

        // El consumer procesa el evento de forma asíncrona vía RabbitListener;
        // esperamos activamente el estado real en BD en vez de asumir timing fijo.
        await().atMost(TIMEOUT)
                .pollInterval(POLL)
                .untilAsserted(() -> {
                    Payment payment = paymentRepository.findById(paymentId).orElseThrow();
                    assertThat(payment.getStatus()).isEqualTo(expectedStatus);
                });
    }

    private String readStatusFromDb(UUID paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }
}
