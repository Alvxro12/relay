package io.github.alvxro12.relay.payment.service;

import io.github.alvxro12.relay.payment.Payment;
import io.github.alvxro12.relay.payment.PaymentRepository;
import io.github.alvxro12.relay.payment.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Detecta pagos en UNKNOWN sin resolver por tiempo prolongado.
 * No reintenta el cobro automáticamente: sin capacidad de consulta
 * al proveedor real (ver PaymentProvider), reintentar arriesgaría
 * doble cobro. Requiere revisión manual vía GET /payments/needs-review.
 */
@Component
public class PaymentReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);
    private static final long STALE_THRESHOLD_MINUTES = 15;

    private final PaymentRepository paymentRepository;

    public PaymentReconciliationJob(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void logStalePayments() {
        List<Payment> stale = findStalePayments();
        if (!stale.isEmpty()) {
            log.warn("Reconciliation: {} payment(s) stuck in UNKNOWN for over {} minutes, need manual review: {}",
                    stale.size(), STALE_THRESHOLD_MINUTES,
                    stale.stream().map(p -> p.getId().toString()).toList());
        }
    }

    List<Payment> findStalePayments() {
        Instant threshold = Instant.now().minus(STALE_THRESHOLD_MINUTES, ChronoUnit.MINUTES);
        return paymentRepository.findByStatusAndUpdatedAtBefore(PaymentStatus.UNKNOWN, threshold);
    }
}