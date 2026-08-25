package io.github.alvxro12.relay.payment;

import io.github.alvxro12.relay.payment.dto.CreatePaymentRequest;
import io.github.alvxro12.relay.payment.service.PaymentService;
import io.github.alvxro12.relay.provider.FakePaymentProvider;
import io.github.alvxro12.relay.provider.ProviderPaymentStatus;
import io.github.alvxro12.relay.provider.ProviderStatusResult;
import io.github.alvxro12.relay.provider.SandboxScenario;
import io.github.alvxro12.relay.provider.FakePaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
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
        // El resultado del cobro ya no es estado del bean: lo elige la referencia de cada
        // pago, asi que no hay nada que resetear ahi. Lo que si es estado compartido es el
        // hook y el contador.
        fakePaymentProvider.onNextCharge(null);
        fakePaymentProvider.resetChargeCount();
    }

    @Test
    void success_marksPaymentAsSucceeded() {
        assertFinalStatus(SandboxScenario.SUCCESS, PaymentStatus.SUCCEEDED);
    }

    /**
     * ACCEPTED no es un resultado: es el proveedor haciéndose cargo del cobro. El pago
     * no puede quedar en PENDING —volvería a ser candidato a cobrarse— ni en un estado
     * terminal, porque todavía no se sabe cómo termina.
     */
    @Test
    void accepted_marksPaymentAsAwaitingConfirmation() {
        assertFinalStatus(SandboxScenario.AWAITING_CONFIRMATION, PaymentStatus.AWAITING_CONFIRMATION);
    }

    @Test
    void declined_marksPaymentAsFailed() {
        assertFinalStatus(SandboxScenario.FAILED, PaymentStatus.FAILED);
    }

    @Test
    void serverError_marksPaymentAsUnknown() {
        assertFinalStatus(SandboxScenario.UNKNOWN, PaymentStatus.UNKNOWN);
    }

    @Test
    void timeout_marksPaymentAsUnknown() {
        assertFinalStatus(SandboxScenario.TIMEOUT, PaymentStatus.UNKNOWN);
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
        UUID merchantId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentResult result = paymentService.createPayment(
                merchantId, idempotencyKey, new CreatePaymentRequest(1000L, "USD", "redelivery"));
        Payment created = result.payment();
        UUID paymentId = created.getId();

        // El id de transaccion es determinístico por pago, asi que se calcula en vez de
        // forzarse antes del cobro.
        String providerTransactionId = FakePaymentProvider.transactionIdFor(paymentId);

        // La segunda entrega, publicada a mano: el broker reentregando el mismo mensaje
        // se ve exactamente así desde el lado del consumer.
        paymentEventPublisher.publishChargeRequested(new ChargeRequestedEvent(
                paymentId, merchantId, created.getAmount(), created.getCurrency(),
                created.getExternalReference()));

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
     * El claim deja registrado cuando se llamo al proveedor. Sin esa marca, la ventana de
     * gracia de NOT_FOUND no tiene desde donde medirse y no puede frenar nada.
     */
    @Test
    void claim_recordsWhenTheChargeWasAttempted() {
        Instant beforeCharge = Instant.now();

        UUID merchantId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentResult result = paymentService.createPayment(merchantId, idempotencyKey,
                new CreatePaymentRequest(1000L, "USD", "charge-attempted-at"));
        UUID paymentId = result.payment().getId();

        await().atMost(TIMEOUT).pollInterval(POLL).untilAsserted(() ->
                assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                        .isEqualTo(PaymentStatus.SUCCEEDED));

        assertThat(paymentRepository.findById(paymentId).orElseThrow().getChargeAttemptedAt())
                .as("el claim tiene que dejar la marca del intento de cobro")
                .isNotNull()
                .isAfterOrEqualTo(beforeCharge.minusSeconds(1));
    }

    /**
     * Un cobro que se aplico y despues se rompio por algo que no es un timeout deja el pago
     * colgado en PROCESSING <b>con plata movida</b>.
     *
     * <p>Es el unico camino que produce ese estado, y por eso existe el escenario: sin el
     * no hay con que probar despues que un PROCESSING stale se resuelve preguntandole al
     * proveedor. Los otros escenarios de falla terminan todos en UNKNOWN.
     *
     * <p>El mecanismo es la ausencia de un catch: PaymentChargeService atrapa solamente
     * PaymentProviderTimeoutException, asi que esta excepcion sube sin manejar, recordResult
     * nunca corre y el claim queda commiteado en PROCESSING para siempre.
     *
     * <p>El listener va a loguear la excepcion y reintentar. Es lo esperado, no un test
     * roto: en el reintento el claim encuentra el pago ya en PROCESSING, se va sin cobrar y
     * el mensaje se ackea. De ahi que el cobro sea exactamente uno.
     */
    @Test
    void crashAfterCharge_leavesThePaymentStuckInProcessingWithTheChargeApplied() {
        UUID merchantId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentResult result = paymentService.createPayment(merchantId, idempotencyKey,
                new CreatePaymentRequest(1000L, "USD",
                        SandboxScenario.PREFIX + SandboxScenario.CRASH_AFTER_CHARGE.name()));
        UUID paymentId = result.payment().getId();

        await().atMost(TIMEOUT).pollInterval(POLL).untilAsserted(() ->
                assertThat(readStatusFromDb(paymentId)).isEqualTo(PaymentStatus.PROCESSING.name()));

        // Y se queda ahi. Sostenida en el tiempo porque el fallo seria una escritura tardia:
        // un chequeo instantaneo podria adelantarse al reintento del listener y pasar por
        // accidente.
        await().during(Duration.ofSeconds(3)).atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(readStatusFromDb(paymentId))
                    .as("nadie resuelve un PROCESSING colgado por su cuenta")
                    .isEqualTo(PaymentStatus.PROCESSING.name());

            assertThat(fakePaymentProvider.chargeCount())
                    .as("el reintento del listener no vuelve a cobrar: el claim lo frena")
                    .isEqualTo(1);
        });

        // La parte que hace util al escenario: el pago se ve como si no hubiera pasado nada,
        // y del otro lado hay un cobro exitoso esperando a que alguien pregunte.
        assertThat(fakePaymentProvider.getPaymentStatus(paymentId))
                .as("el proveedor cobro, aunque el pago no lo refleje")
                .isEqualTo(new ProviderStatusResult(
                        ProviderPaymentStatus.SUCCEEDED, FakePaymentProvider.transactionIdFor(paymentId)));

        assertThat(paymentRepository.findById(paymentId).orElseThrow().getProviderTransactionId())
                .as("y el pago no tiene ni la clave con la que correlacionarlo")
                .isNull();
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

    /**
     * La carrera que la guarda de {@code recordResult} tiene que cubrir. Entre el claim y
     * el recordResult la fila queda commiteada en PROCESSING y sin lock, así que un tercero
     * puede resolver el pago mientras el cobro está en vuelo. Acá esa escritura ajena se
     * hace con JdbcTemplate —otra conexión, sin JPA de por medio, que es exactamente lo que
     * vería el consumer de charge si el de webhook pudiera correlacionar antes— y después
     * el cobro vuelve con su propio resultado. El estado terminal tiene que quedar en pie.
     *
     * <p>No se usa el consumer de webhook real para provocarlo porque hoy no puede: recién
     * correlaciona cuando recordResult escribió el providerTransactionId, y para entonces
     * la carrera ya pasó. Esa ventana es un accidente del diseño actual, no la garantía; el
     * test ataca la garantía.
     *
     * <p>Se verificó que discrimina: contra el recordResult sin guarda falla con
     * {@code expected: FAILED but was: SUCCEEDED}.
     */
    @Test
    void terminalStatusWrittenMidCharge_isNotOverwrittenByTheChargeResult() throws Exception {
        CountDownLatch chargeInFlight = new CountDownLatch(1);
        CountDownLatch releaseCharge = new CountDownLatch(1);

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
                merchantId, idempotencyKey, new CreatePaymentRequest(1000L, "USD", "terminal-race"));
        UUID paymentId = result.payment().getId();
        String providerTransactionId = FakePaymentProvider.transactionIdFor(paymentId);

        assertThat(chargeInFlight.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .as("el consumer de charge tiene que haber entrado al provider")
                .isTrue();
        assertThat(readStatusFromDb(paymentId))
                .as("el pago tiene que estar tomado y commiteado antes de la escritura ajena")
                .isEqualTo(PaymentStatus.PROCESSING.name());

        // El tercero resuelve el pago mientras el cobro sigue afuera.
        jdbcTemplate.update("UPDATE payments SET status = ? WHERE id = ?",
                PaymentStatus.FAILED.name(), paymentId);

        releaseCharge.countDown();

        // El listener no sabe nada de esa escritura y llama a recordResult igual. La
        // aserción se sostiene en el tiempo porque el fallo sería una escritura que llega
        // tarde: un chequeo instantáneo podría adelantarse a ella y pasar por accidente.
        await().during(Duration.ofSeconds(2)).atMost(TIMEOUT).untilAsserted(() -> {
            Payment payment = paymentRepository.findById(paymentId).orElseThrow();
            assertThat(payment.getStatus())
                    .as("el estado terminal escrito durante el cobro no se pisa")
                    .isEqualTo(PaymentStatus.FAILED);

            // El status se descarta, pero el id del proveedor no: es la única clave con la
            // que este pago puede correlacionarse después, y el estado terminal llegó sin
            // ella. Descartarla dejaría el pago sin correlación para siempre.
            assertThat(payment.getProviderTransactionId())
                    .as("la correlación con el proveedor se persiste aunque el status se descarte")
                    .isEqualTo(providerTransactionId);
        });
    }

    /**
     * El escenario se pide con la referencia del pago, que es como lo pide un merchant en
     * modo sandbox. No hay estado que forzar en el bean antes de cobrar, asi que dos tests
     * concurrentes no pueden pisarse.
     *
     * <p>Se le pega un sufijo con la idempotency key para probar de paso que el sufijo del
     * merchant no rompe el parseo del escenario.
     */
    private void assertFinalStatus(SandboxScenario scenario, PaymentStatus expectedStatus) {
        UUID merchantId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        CreatePaymentRequest request = new CreatePaymentRequest(
                1000L, "USD", SandboxScenario.PREFIX + scenario.name() + ":order-" + idempotencyKey);

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
