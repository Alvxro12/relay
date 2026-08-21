package io.github.alvxro12.relay.payment.service;

import io.github.alvxro12.relay.payment.Payment;
import io.github.alvxro12.relay.payment.PaymentRepository;
import io.github.alvxro12.relay.payment.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
