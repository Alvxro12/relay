package io.github.alvxro12.relay.payment.service;

import io.github.alvxro12.relay.payment.Payment;
import io.github.alvxro12.relay.payment.PaymentRepository;
import io.github.alvxro12.relay.payment.PaymentStatus;
import io.github.alvxro12.relay.provider.ChargeRequest;
import io.github.alvxro12.relay.provider.FakePaymentProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * La edad de los pagos se siembra con {@code JdbcTemplate} y no esperando: {@code updated_at}
 * lo escribe la auditoría de JPA y {@code charge_attempted_at} no tiene setter, así que la
 * única forma de tener un pago viejo sin dormir el test es escribir los timestamps por SQL
 * directo.
 *
 * <p>Se llama al resolver directo y no se espera al {@code @Scheduled}: el disparo periódico
 * está apagado en la corrida de tests (ver {@code PaymentReconciliationJob} y el pom), porque
 * un job escribiendo de fondo mutaría las filas que los demás tests están mirando.
 */
@SpringBootTest
class StalePaymentResolverTest {

    @Autowired
    private StalePaymentResolver resolver;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakePaymentProvider fakePaymentProvider;

    @Test
    void findsPaymentsStuckInUnknownForOver15Minutes() {
        Payment stale = stalePayment(PaymentStatus.UNKNOWN, null, 20, ChronoUnit.MINUTES);
        Payment recent = payment(PaymentStatus.UNKNOWN, null);

        List<Payment> result = resolver.findStalePayments();

        assertThat(result).extracting(Payment::getId)
                .contains(stale.getId())
                .doesNotContain(recent.getId());
    }

    /**
     * El consumer tomó el cobro y murió antes de registrar el resultado. Ya se observó en
     * la práctica: el CHECK constraint desactualizado hacía fallar el `recordResult` y el
     * pago quedaba acá para siempre. El umbral es corto porque PROCESSING dura lo que dura
     * la llamada al proveedor: a los minutos ya no hay nadie del otro lado.
     */
    @Test
    void findsPaymentsStuckInProcessing() {
        Payment stale = stalePayment(PaymentStatus.PROCESSING, null, 10, ChronoUnit.MINUTES);
        Payment recent = payment(PaymentStatus.PROCESSING, null);

        List<Payment> result = resolver.findStalePayments();

        assertThat(result).extracting(Payment::getId)
                .contains(stale.getId())
                .doesNotContain(recent.getId());
    }

    /**
     * El proveedor aceptó el cobro y el webhook de confirmación nunca llegó. Siempre hay
     * providerTransactionId —es lo que hace útil al estado—, así que se siembra con uno:
     * su presencia es parte de lo que distingue esta investigación de las otras dos.
     */
    @Test
    void findsPaymentsStuckInAwaitingConfirmation() {
        String providerTransactionId = "fake_txn_stale_" + UUID.randomUUID();
        Payment stale = stalePayment(
                PaymentStatus.AWAITING_CONFIRMATION, providerTransactionId, 8, ChronoUnit.HOURS);
        Payment recent = payment(PaymentStatus.AWAITING_CONFIRMATION, "fake_txn_recent_" + UUID.randomUUID());

        List<Payment> result = resolver.findStalePayments();

        assertThat(result).extracting(Payment::getId)
                .contains(stale.getId())
                .doesNotContain(recent.getId());
    }

    /**
     * Los umbrales son por estado y no uno global, y esto es lo que lo prueba: dos pagos
     * de exactamente la misma edad, uno adentro de su ventana y el otro afuera. Veinte
     * minutos es viejo para un UNKNOWN pero es el flujo feliz de un AWAITING_CONFIRMATION,
     * que espera un webhook del proveedor y puede tardar horas legítimamente. Marcarlo
     * sería alertar sobre un pago que no tiene nada de malo.
     */
    @Test
    void doesNotFlagPaymentsStillWithinTheirOwnThreshold() {
        Payment unknown = stalePayment(PaymentStatus.UNKNOWN, null, 20, ChronoUnit.MINUTES);
        Payment awaiting = stalePayment(
                PaymentStatus.AWAITING_CONFIRMATION, "fake_txn_young_" + UUID.randomUUID(),
                20, ChronoUnit.MINUTES);

        List<Payment> result = resolver.findStalePayments();

        assertThat(result).extracting(Payment::getId)
                .as("misma edad, pero solo el UNKNOWN pasó su umbral")
                .contains(unknown.getId())
                .doesNotContain(awaiting.getId());
    }

    /**
     * La reconciliacion no cobra. Nunca.
     *
     * <p>Es la guarda mas importante del Gate 2, y ahora que la reconciliacion si llama al
     * proveedor es cuando empieza a decir algo: los tres pagos tienen la marca de intento
     * de cobro puesta, asi que la pasada los consulta de verdad. Lo que se afirma es que de
     * todo ese ida y vuelta con el proveedor, {@code charge()} no aparece nunca.
     *
     * <p>Por que importa tanto: un pago colgado en PROCESSING o UNKNOWN es, por
     * definicion, uno del que no sabemos si el cobro movio plata. Reintentar el cobro
     * "por las dudas" es la forma mas cara de equivocarse que tiene este sistema —le
     * cobra dos veces a una persona real— y es tentadora justamente porque deja el estado
     * final lindo. La salida correcta es preguntar, no reintentar.
     *
     * <p>Se cuenta con {@code chargeCount()} y no con un spy de Mockito porque
     * {@code @MockitoSpyBean} entra en la clave del cache de contextos: esa clase de test
     * levantaria un segundo ApplicationContext cuyos @RabbitListener competirian por las
     * mismas colas que este. Para "nunca se llamo", el contador es equivalente a
     * {@code verify(never())}.
     */
    @Test
    void reconciliationNeverCallsCharge() {
        // Los tres estados que la reconciliacion mira, todos vencidos: si el job fuera a
        // cobrar algo, estos son los pagos por los que lo haria.
        stalePayment(PaymentStatus.UNKNOWN, null, 20, ChronoUnit.MINUTES);
        stalePayment(PaymentStatus.PROCESSING, null, 10, ChronoUnit.MINUTES);
        stalePayment(PaymentStatus.AWAITING_CONFIRMATION,
                "fake_txn_guard_" + UUID.randomUUID(), 8, ChronoUnit.HOURS);

        // Linea base en vez de reset a cero: un cobro asincronico de otra clase de test
        // puede seguir en vuelo, y lo que se afirma no es "nadie cobro nunca" sino "el
        // job no cobro".
        int chargesBeforeJob = fakePaymentProvider.chargeCount();

        resolver.reconcileStalePayments();

        assertThat(fakePaymentProvider.chargeCount())
                .as("la reconciliacion no llamo al proveedor")
                .isEqualTo(chargesBeforeJob);

        // Sostenida en el tiempo: si el job publicara un evento en vez de cobrar en linea,
        // el cobro apareceria unos milisegundos despues y la asercion de arriba lo dejaria
        // pasar.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(fakePaymentProvider.chargeCount())
                        .as("tampoco cobro por un camino asincronico")
                        .isEqualTo(chargesBeforeJob));
    }

    /**
     * El caso base de la recuperación, en su forma más chica: el proveedor tiene la
     * respuesta y la reconciliación la va a buscar.
     */
    @Test
    void aStalePaymentIsResolvedByAskingTheProvider() {
        Payment stale = stalePayment(PaymentStatus.PROCESSING, null, 10, ChronoUnit.MINUTES);
        chargeAtTheProvider(stale.getId());

        resolver.reconcileStalePayments();

        Payment resolved = paymentRepository.findById(stale.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(resolved.getProviderTransactionId())
                .as("y de paso se rescata la clave con la que correlacionarlo")
                .isEqualTo(FakePaymentProvider.transactionIdFor(stale.getId()));
    }

    /**
     * Pasado el plazo se deja de preguntar, y esto es lo que lo prueba: el proveedor
     * <b>tiene</b> la respuesta —el mismo montaje que el test de arriba, que resuelve— y el
     * pago igual se queda donde está. La única diferencia es la edad de la marca de intento
     * de cobro. Si la consulta se hiciera igual, el pago terminaría en SUCCEEDED.
     *
     * <p>Que se quede en PROCESSING no es el limbo: sigue saliendo por
     * {@code GET /v1/payments/needs-review} y por el WARN de cada ciclo, ahora en el grupo
     * de los que esperan a una persona. Lo que se corta es la consulta infinita.
     */
    @Test
    void aPaymentPastTheGiveUpDeadlineIsNotAskedAboutAnyMore() {
        Payment stale = stalePayment(PaymentStatus.PROCESSING, null, 10, ChronoUnit.MINUTES);
        chargeAtTheProvider(stale.getId());
        forceChargeAttemptedAt(stale.getId(), Instant.now().minus(25, ChronoUnit.HOURS));

        resolver.reconcileStalePayments();

        assertThat(paymentRepository.findById(stale.getId()).orElseThrow().getStatus())
                .as("el proveedor sabía la respuesta, pero ya no se le pregunta")
                .isEqualTo(PaymentStatus.PROCESSING);
    }

    /**
     * Sin marca de intento de cobro no hay reloj: no se puede medir ni el plazo de give-up
     * ni la ventana de gracia del NOT_FOUND, que es la única respuesta capaz de resolver
     * este pago por la negativa. Consultarlo sería consultarlo para siempre. Son filas
     * anteriores a la migración que agregó la columna, y van derecho a revisión manual.
     */
    @Test
    void aPaymentWithoutTheChargeAttemptMarkIsNeverAskedAbout() {
        Payment stale = stalePayment(PaymentStatus.PROCESSING, null, 10, ChronoUnit.MINUTES);
        chargeAtTheProvider(stale.getId());
        forceChargeAttemptedAt(stale.getId(), null);

        resolver.reconcileStalePayments();

        assertThat(paymentRepository.findById(stale.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PROCESSING);
    }

    /**
     * El proveedor lo tiene tomado y todavía no lo resolvió. El pago no se queda en
     * PROCESSING —que significa "el consumer murió", y ya sabemos que no fue eso— sino que
     * pasa a AWAITING_CONFIRMATION, que además lo mueve del umbral de 2m al de 6h: el que le
     * corresponde a algo que espera un webhook.
     */
    @Test
    void aChargeTheProviderStillHasPendingBecomesAwaitingConfirmation() {
        Payment stale = stalePayment(PaymentStatus.PROCESSING, null, 10, ChronoUnit.MINUTES);
        chargeAtTheProvider(stale.getId(), "AWAITING_CONFIRMATION");

        resolver.reconcileStalePayments();

        Payment resolved = paymentRepository.findById(stale.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(PaymentStatus.AWAITING_CONFIRMATION);
        assertThat(resolved.getProviderTransactionId())
                .as("y ahora sí tiene con qué correlacionar el webhook que va a llegar")
                .isEqualTo(FakePaymentProvider.transactionIdFor(stale.getId()));
    }

    /**
     * Un pago que la consulta no resuelve <b>no se toca</b>, y eso incluye {@code updated_at}.
     *
     * <p>No es cosmético: el staleness se mide con esa columna, así que una escritura inútil
     * sacaría al pago de su propia ventana y la reconciliación no lo volvería a ver hasta que
     * envejeciera otra vez. Un UNKNOWN consultado cada cinco minutos y tocado cada vez no se
     * consultaría nunca más — el mismo problema que obligó a crear {@code charge_attempted_at}
     * en vez de medir la gracia desde {@code updated_at}, volviendo por otro lado.
     */
    @Test
    void aPaymentTheInquiryCannotResolveIsNotWrittenAtAll() {
        Payment stale = stalePayment(PaymentStatus.UNKNOWN, null, 20, ChronoUnit.MINUTES);
        // El proveedor lo tiene registrado y tampoco sabe cómo terminó: la respuesta que no
        // habilita ninguna decisión.
        chargeAtTheProvider(stale.getId(), "UNKNOWN");
        Instant updatedAtBefore = readUpdatedAt(stale.getId());

        resolver.reconcileStalePayments();

        assertThat(paymentRepository.findById(stale.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(readUpdatedAt(stale.getId()))
                .as("no se escribió la fila, así que sigue vencida y se vuelve a consultar")
                .isEqualTo(updatedAtBefore);
        assertThat(resolver.findStalePayments())
                .extracting(Payment::getId)
                .contains(stale.getId());
    }

    /**
     * Hace pasar por el proveedor simulado un cobro para este paymentId, que es lo que
     * después hace que la consulta de estado tenga algo que contestar. Se llama directo y no
     * por la cola: acá lo que se prueba es la reconciliación, y el cobro es el montaje.
     */
    private void chargeAtTheProvider(UUID paymentId) {
        chargeAtTheProvider(paymentId, null);
    }

    private void chargeAtTheProvider(UUID paymentId, String scenario) {
        String reference = scenario == null ? null : "RELAY_TEST_" + scenario;
        try {
            fakePaymentProvider.charge(new ChargeRequest(paymentId, 1000L, "USD", reference));
        } catch (RuntimeException e) {
            // Varios escenarios registran el cobro y después lanzan. Es justo lo que hace
            // falta acá: el registro del lado del proveedor.
        }
    }

    private Payment payment(PaymentStatus status, String providerTransactionId) {
        Payment payment = new Payment();
        payment.setMerchantId(UUID.randomUUID());
        payment.setIdempotencyKey(UUID.randomUUID().toString());
        payment.setAmount(1000L);
        payment.setCurrency("USD");
        payment.setStatus(status);
        payment.setProviderTransactionId(providerTransactionId);
        payment.markChargeAttempted(Instant.now());
        return paymentRepository.saveAndFlush(payment);
    }

    /**
     * Un pago vencido. La marca de intento de cobro se envejece junto con {@code updated_at}:
     * un pago que lleva diez minutos colgado es uno que se intentó cobrar hace diez minutos, y
     * las dos ventanas —la de staleness y la de gracia— se miden sobre esa historia.
     */
    private Payment stalePayment(PaymentStatus status, String providerTransactionId,
                                 long age, ChronoUnit unit) {
        Payment saved = payment(status, providerTransactionId);
        Instant aged = Instant.now().minus(age, unit);
        forceUpdatedAt(saved.getId(), aged);
        forceChargeAttemptedAt(saved.getId(), aged);
        return saved;
    }

    private void forceUpdatedAt(UUID paymentId, Instant timestamp) {
        jdbcTemplate.update(
                "UPDATE payments SET updated_at = ? WHERE id = ?",
                atUtc(timestamp), paymentId
        );
    }

    /** Por SQL porque el dominio la escribe una sola vez y no la deja mover. */
    private void forceChargeAttemptedAt(UUID paymentId, Instant timestamp) {
        jdbcTemplate.update(
                "UPDATE payments SET charge_attempted_at = ? WHERE id = ?",
                atUtc(timestamp), paymentId
        );
    }

    /**
     * {@code OffsetDateTime} en UTC y no {@code Timestamp}: las columnas son
     * {@code datetimeoffset}, y un {@code java.sql.Timestamp} lo manda el driver como la hora
     * <b>local</b> de la JVM etiquetada {@code +00:00}. El instante guardado termina corrido
     * el offset de la máquina —cinco horas acá—, siempre hacia atrás.
     *
     * <p>El error es invisible mientras un test solo pida "suficientemente viejo": correrlo
     * más hacia el pasado no cambia el resultado. Deja de serlo apenas se prueba una ventana
     * por dentro, que es justo lo que hace la gracia del NOT_FOUND.
     */
    private static OffsetDateTime atUtc(Instant timestamp) {
        return timestamp == null ? null : timestamp.atOffset(ZoneOffset.UTC);
    }

    private Instant readUpdatedAt(UUID paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow().getUpdatedAt();
    }
}
