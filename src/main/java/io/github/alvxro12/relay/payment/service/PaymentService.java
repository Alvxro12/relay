package io.github.alvxro12.relay.payment.service;

import io.github.alvxro12.relay.payment.Payment;
import io.github.alvxro12.relay.payment.PaymentRepository;
import io.github.alvxro12.relay.payment.PaymentResult;
import io.github.alvxro12.relay.payment.PaymentStatus;
import io.github.alvxro12.relay.payment.dto.CreatePaymentRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentReconciliationService reconciliationService;

    private final PaymentInsertService paymentInsertService;

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentReconciliationService reconciliationService,
                           PaymentInsertService paymentInsertService,
                           PaymentRepository paymentRepository) {
        this.reconciliationService = reconciliationService;
        this.paymentInsertService = paymentInsertService;
        this.paymentRepository = paymentRepository;
    }

    public PaymentResult createPayment(UUID merchantId, String idempotencyKey, CreatePaymentRequest request) {

        Optional<Payment> existing = paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);

        if (existing.isPresent()) {
            return resolveExisting(existing.get(), request);
        }

        try {
            Payment saved = paymentInsertService.insert(merchantId, idempotencyKey, request);
            return PaymentResult.created(saved);

        } catch (DataIntegrityViolationException e) {
            Payment winner = reconciliationService.findWinner(merchantId, idempotencyKey);
            return resolveExisting(winner, request);
        }
    }

    private PaymentResult resolveExisting(Payment existing, CreatePaymentRequest request) {
        boolean sameBody = existing.getAmount().equals(request.amount())
                && existing.getCurrency().equals(request.currency());

        if (sameBody) {
            return PaymentResult.replay(existing);
        }
        return PaymentResult.conflict(existing);
    }

    public Optional<Payment> findById(UUID merchantId, UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .filter(p -> p.getMerchantId().equals(merchantId));
    }

    /**
     * Pagos del merchant que quedaron sin resolver y necesitan revisión manual.
     *
     * <p>El scope por merchant no es opcional: son datos de un comercio y un comercio
     * no tiene por qué ver los de otro. Mismo criterio que {@link #findById}.
     */
    public List<Payment> findPaymentsNeedingReview(UUID merchantId, long olderThanMinutes) {
        Instant threshold = Instant.now().minus(olderThanMinutes, ChronoUnit.MINUTES);
        return paymentRepository.findByMerchantIdAndStatusAndUpdatedAtBefore(
                merchantId, PaymentStatus.UNKNOWN, threshold);
    }
}