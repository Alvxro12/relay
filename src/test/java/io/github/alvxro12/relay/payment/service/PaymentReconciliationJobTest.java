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
        Payment stale = createUnknownPayment();
        forceUpdatedAt(stale.getId(), Instant.now().minus(20, ChronoUnit.MINUTES));

        Payment recent = createUnknownPayment();

        List<Payment> result = reconciliationJob.findStalePayments();

        assertThat(result).extracting(Payment::getId)
                .contains(stale.getId())
                .doesNotContain(recent.getId());
    }

    private Payment createUnknownPayment() {
        Payment payment = new Payment();
        payment.setMerchantId(UUID.randomUUID());
        payment.setIdempotencyKey(UUID.randomUUID().toString());
        payment.setAmount(1000L);
        payment.setCurrency("USD");
        payment.setStatus(PaymentStatus.UNKNOWN);
        return paymentRepository.saveAndFlush(payment);
    }

    private void forceUpdatedAt(UUID paymentId, Instant timestamp) {
        jdbcTemplate.update(
                "UPDATE payments SET updated_at = ? WHERE id = ?",
                Timestamp.from(timestamp), paymentId
        );
    }
}