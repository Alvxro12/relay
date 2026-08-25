package io.github.alvxro12.relay.payment;

import io.github.alvxro12.relay.payment.dto.CreatePaymentRequest;
import io.github.alvxro12.relay.payment.service.PaymentService;
import io.github.alvxro12.relay.payment.service.StalePaymentResolver;
import io.github.alvxro12.relay.provider.FakePaymentProvider;
import io.github.alvxro12.relay.provider.SandboxScenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * El corazón del Gate 2: un fallo de infraestructura después de {@code provider.charge()} no
 * pierde el resultado ni cobra dos veces.
 *
 * <p>Cada test recorre el camino entero —crear el pago, cobrarlo por la cola, que el cobro se
 * rompa de una forma distinta, y después una pasada de reconciliación— y lo que verifica es
 * el desenlace. Los cuatro escenarios se ven <b>igual desde Relay</b> antes de reconciliar: un
 * pago colgado, sin resultado. Lo único que los distingue es lo que el proveedor sabe, y la
 * consulta de estado es la forma de enterarse.
 *
 * <p>Los timestamps se envejecen por SQL en vez de esperar: las ventanas reales son de
 * minutos y horas. Y la reconciliación se invoca directo en vez de esperar al
 * {@code @Scheduled}, que está apagado en la corrida de tests.
 */
@SpringBootTest
class PaymentRecoveryIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL = Duration.ofMillis(100);

    /** Más viejo que la ventana de gracia del NOT_FOUND (30m) y que todos los umbrales de staleness. */
    private static final Duration PAST_THE_GRACE_WINDOW = Duration.ofMinutes(40);

    /** Vencido para un UNKNOWN (15m) pero todavía adentro de la gracia del NOT_FOUND (30m). */
    private static final Duration WITHIN_THE_GRACE_WINDOW = Duration.ofMinutes(20);

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private StalePaymentResolver resolver;

    @Autowired
    private FakePaymentProvider fakePaymentProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetHook() {
        fakePaymentProvider.onNextCharge(null);
    }

    /**
     * <b>El provider cobró y la respuesta se perdió.</b> Es el caso que justifica el gate
     * entero: hay plata movida y Relay no se enteró. Desde acá el pago se ve idéntico a un
     * timeout que nunca cobró —mismo estado, mismo error, sin providerTransactionId—, y la
     * única forma de distinguirlos es preguntar.
     */
    @Test
    void aChargeWhoseResponseWasLost_isRecoveredAsSucceeded() {
        UUID paymentId = chargeAndAwait(SandboxScenario.LOST_RESPONSE, PaymentStatus.UNKNOWN);
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getProviderTransactionId())
                .as("antes de reconciliar no hay ni con qué correlacionarlo")
                .isNull();

        int chargesBefore = fakePaymentProvider.chargeCount();
        age(paymentId, PAST_THE_GRACE_WINDOW);

        resolver.reconcileStalePayments();

        Payment recovered = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(recovered.getStatus())
                .as("el cobro había salido bien; la reconciliación lo descubre preguntando")
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(recovered.getProviderTransactionId())
                .as("y recupera la clave de correlación que el cobro nunca llegó a devolver")
                .isEqualTo(FakePaymentProvider.transactionIdFor(paymentId));
        assertNoExtraCharges(chargesBefore);
    }

    /**
     * <b>El provider nunca cobró.</b> El timeout cortó antes de que la llamada llegara, así
     * que del otro lado no quedó nada: la consulta devuelve NOT_FOUND, la única respuesta que
     * autoriza a dar el pago por fallido, y acá ya pasó la ventana de gracia.
     */
    @Test
    void aChargeThatNeverReachedTheProvider_isResolvedAsFailed() {
        UUID paymentId = chargeAndAwait(SandboxScenario.TIMEOUT, PaymentStatus.UNKNOWN);

        int chargesBefore = fakePaymentProvider.chargeCount();
        age(paymentId, PAST_THE_GRACE_WINDOW);

        resolver.reconcileStalePayments();

        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        assertNoExtraCharges(chargesBefore);
    }

    /**
     * El mismo pago del test de arriba, con una sola diferencia: todavía está adentro de la
     * ventana de gracia. Y ahí un NOT_FOUND no alcanza.
     *
     * <p>El motivo es que un proveedor real puede contestar NOT_FOUND por un cobro que
     * todavía está creando de su lado —sus lecturas no son inmediatamente consistentes con
     * sus escrituras—, y actuar sobre eso le dice a un merchant que no cobró cuando sí cobró.
     * El error es asimétrico: esperar de más hace esperar, fallar de más pierde plata que
     * nadie revierte.
     */
    @Test
    void aNotFoundWithinTheGraceWindow_doesNotFailThePaymentYet() {
        UUID paymentId = chargeAndAwait(SandboxScenario.TIMEOUT, PaymentStatus.UNKNOWN);

        int chargesBefore = fakePaymentProvider.chargeCount();
        age(paymentId, WITHIN_THE_GRACE_WINDOW);

        resolver.reconcileStalePayments();

        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .as("todavía no le creemos al NOT_FOUND")
                .isEqualTo(PaymentStatus.UNKNOWN);
        assertNoExtraCharges(chargesBefore);
    }

    /**
     * <b>El provider responde UNKNOWN.</b> Tiene el cobro registrado y él mismo no sabe cómo
     * terminó, así que no habilita ninguna decisión: hay plata que pudo haberse movido y
     * cualquier escritura sería una adivinanza. El pago se queda para revisión manual, y —lo
     * que importa— sin cobro duplicado.
     */
    @Test
    void aProviderThatDoesNotKnowEither_leavesThePaymentForReviewWithoutRecharging() {
        UUID paymentId = chargeAndAwait(SandboxScenario.UNKNOWN, PaymentStatus.UNKNOWN);

        int chargesBefore = fakePaymentProvider.chargeCount();
        age(paymentId, PAST_THE_GRACE_WINDOW);

        resolver.reconcileStalePayments();

        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .as("ni SUCCEEDED ni FAILED: nadie sabe, y adivinar es lo que no se hace")
                .isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(resolver.findStalePayments())
                .extracting(Payment::getId)
                .as("y sigue visible para que lo mire una persona, no en limbo silencioso")
                .contains(paymentId);
        assertNoExtraCharges(chargesBefore);
    }

    /**
     * <b>Un PROCESSING colgado se resuelve preguntando.</b> Es el único camino que deja un
     * pago en PROCESSING con plata movida: el cliente del proveedor se rompió después de
     * cobrar, con una excepción que no es un timeout, así que {@code recordResult} nunca
     * corrió.
     *
     * <p>Y es el caso donde reintentar el cobro sería más tentador —el pago se ve como si no
     * hubiera pasado nada— y más caro: le cobraría dos veces a una persona real.
     */
    @Test
    void aPaymentStuckInProcessingWithMoneyMoved_isResolvedByInquiry() {
        UUID paymentId = chargeAndAwait(SandboxScenario.CRASH_AFTER_CHARGE, PaymentStatus.PROCESSING);

        int chargesBefore = fakePaymentProvider.chargeCount();
        age(paymentId, PAST_THE_GRACE_WINDOW);

        resolver.reconcileStalePayments();

        Payment recovered = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(recovered.getProviderTransactionId())
                .isEqualTo(FakePaymentProvider.transactionIdFor(paymentId));
        assertNoExtraCharges(chargesBefore);
    }

    /**
     * Crea un pago con el escenario pedido y espera a que el cobro asincrónico lo deje
     * colgado donde corresponde. Ese estado intermedio es el punto de partida de la
     * reconciliación, y esperarlo antes de envejecer la fila no es opcional: si el
     * {@code recordResult} llegara después, reescribiría {@code updated_at} y el pago dejaría
     * de estar vencido.
     */
    private UUID chargeAndAwait(SandboxScenario scenario, PaymentStatus expectedInterimStatus) {
        UUID merchantId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        PaymentResult result = paymentService.createPayment(merchantId, idempotencyKey,
                new CreatePaymentRequest(1000L, "USD",
                        SandboxScenario.PREFIX + scenario.name() + ":order-" + idempotencyKey));
        UUID paymentId = result.payment().getId();

        await().atMost(TIMEOUT).pollInterval(POLL).untilAsserted(() ->
                assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                        .isEqualTo(expectedInterimStatus));

        return paymentId;
    }

    /**
     * Envejece las dos marcas que la reconciliación mira: {@code updated_at}, que define si el
     * pago está colgado, y {@code charge_attempted_at}, desde donde se miden la ventana de
     * gracia del NOT_FOUND y el plazo para dejar de preguntar.
     *
     * <p>Por SQL directo porque a una la escribe la auditoría de JPA y la otra el dominio
     * escribe una sola vez y no deja mover.
     */
    private void age(UUID paymentId, Duration by) {
        // OffsetDateTime en UTC y no Timestamp: las columnas son datetimeoffset, y un
        // java.sql.Timestamp lo manda el driver como la hora local de la JVM etiquetada
        // +00:00, así que el instante guardado queda corrido el offset de la máquina hacia
        // atrás. Da igual mientras un test solo pida "suficientemente viejo"; acá se prueba
        // una ventana por dentro y cinco horas de más la cruzan sola.
        OffsetDateTime aged = Instant.now().minus(by).atOffset(ZoneOffset.UTC);
        jdbcTemplate.update(
                "UPDATE payments SET updated_at = ?, charge_attempted_at = ? WHERE id = ?",
                aged, aged, paymentId);
    }

    /**
     * Lo que separa "quedó en el estado correcto" de "se cobró una sola vez": el estado final
     * se ve igual si el cobro salió una vez o tres. Sostenida en el tiempo por si la
     * reconciliación cobrara por un camino asincrónico en vez de en línea.
     */
    private void assertNoExtraCharges(int chargesBefore) {
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(fakePaymentProvider.chargeCount())
                        .as("la reconciliación resolvió el pago sin volver a cobrar")
                        .isEqualTo(chargesBefore));
    }
}
