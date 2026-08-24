package io.github.alvxro12.relay.payment.service;

import io.github.alvxro12.relay.payment.Payment;
import io.github.alvxro12.relay.payment.PaymentRepository;
import io.github.alvxro12.relay.payment.PaymentStatus;
import io.github.alvxro12.relay.provider.FakePaymentProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * La edad de los pagos se siembra con {@code JdbcTemplate} y no esperando: {@code updated_at}
 * lo escribe la auditoría de JPA, así que la única forma de tener un pago viejo sin dormir
 * el test es escribir el timestamp por SQL directo.
 */
@SpringBootTest
class PaymentReconciliationJobTest {

    @Autowired
    private PaymentReconciliationJob reconciliationJob;

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

        List<Payment> result = reconciliationJob.findStalePayments();

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

        List<Payment> result = reconciliationJob.findStalePayments();

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

        List<Payment> result = reconciliationJob.findStalePayments();

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

        List<Payment> result = reconciliationJob.findStalePayments();

        assertThat(result).extracting(Payment::getId)
                .as("misma edad, pero solo el UNKNOWN pasó su umbral")
                .contains(unknown.getId())
                .doesNotContain(awaiting.getId());
    }

    /**
     * La reconciliacion no cobra. Nunca.
     *
     * <p>Es la guarda mas importante del Gate 2 y esta escrita antes de que la
     * reconciliacion haga nada con el proveedor, a proposito: cuando llegue el momento de
     * que consulte estado, este test ya va a estar puesto y va a decir que no.
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

        reconciliationJob.logStalePayments();

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

    private Payment payment(PaymentStatus status, String providerTransactionId) {
        Payment payment = new Payment();
        payment.setMerchantId(UUID.randomUUID());
        payment.setIdempotencyKey(UUID.randomUUID().toString());
        payment.setAmount(1000L);
        payment.setCurrency("USD");
        payment.setStatus(status);
        payment.setProviderTransactionId(providerTransactionId);
        return paymentRepository.saveAndFlush(payment);
    }

    private Payment stalePayment(PaymentStatus status, String providerTransactionId,
                                 long age, ChronoUnit unit) {
        Payment saved = payment(status, providerTransactionId);
        forceUpdatedAt(saved.getId(), Instant.now().minus(age, unit));
        return saved;
    }

    private void forceUpdatedAt(UUID paymentId, Instant timestamp) {
        jdbcTemplate.update(
                "UPDATE payments SET updated_at = ? WHERE id = ?",
                Timestamp.from(timestamp), paymentId
        );
    }
}
