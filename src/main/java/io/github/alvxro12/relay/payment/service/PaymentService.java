package io.github.alvxro12.relay.payment.service;

import io.github.alvxro12.relay.payment.Payment;
import io.github.alvxro12.relay.payment.PaymentRepository;
import io.github.alvxro12.relay.payment.PaymentResult;
import io.github.alvxro12.relay.payment.PaymentStatus;
import io.github.alvxro12.relay.payment.StalePaymentThresholds;
import io.github.alvxro12.relay.payment.dto.CreatePaymentRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Service
public class PaymentService {

    private final PaymentReconciliationService reconciliationService;

    private final PaymentInsertService paymentInsertService;

    private final PaymentRepository paymentRepository;

    private final StalePaymentThresholds thresholds;

    public PaymentService(PaymentReconciliationService reconciliationService,
                           PaymentInsertService paymentInsertService,
                           PaymentRepository paymentRepository,
                           StalePaymentThresholds thresholds) {
        this.reconciliationService = reconciliationService;
        this.paymentInsertService = paymentInsertService;
        this.paymentRepository = paymentRepository;
        this.thresholds = thresholds;
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
     * Pagos del merchant que quedaron colgados y necesitan revisión manual, cada estado
     * medido contra su propio umbral configurado.
     *
     * <p>El scope por merchant no es opcional: son datos de un comercio y un comercio no
     * tiene por qué ver los de otro. Mismo criterio que {@link #findById}.
     *
     * <p>Esta consulta y la que usa {@link PaymentReconciliationJob} están separadas a
     * propósito y no se unifican: son dos lectores con permisos distintos. El job mira
     * todo el sistema porque es operación interna; el endpoint es una API pública y ahí
     * el filtro por merchant es lo único que separa los datos de un comercio de los de
     * otro. Unificarlas dejaría un solo lugar donde olvidarse del scope.
     */
    public List<Payment> findPaymentsNeedingReview(UUID merchantId) {
        return findNeedingReview(merchantId, thresholds::forStatus);
    }

    /**
     * Igual que {@link #findPaymentsNeedingReview(UUID)} pero con un umbral único que
     * pisa a los tres configurados. Es la forma del parámetro {@code olderThanMinutes}
     * del endpoint: sirve para que un operador afloje o apriete la ventana a mano
     * durante una investigación, no para el uso normal.
     */
    public List<Payment> findPaymentsNeedingReview(UUID merchantId, Duration olderThan) {
        return findNeedingReview(merchantId, status -> olderThan);
    }

    private List<Payment> findNeedingReview(UUID merchantId, Function<PaymentStatus, Duration> thresholdFor) {
        Instant now = Instant.now();
        return StalePaymentThresholds.REVIEWABLE_STATUSES.stream()
                .flatMap(status -> paymentRepository.findByMerchantIdAndStatusAndUpdatedAtBefore(
                        merchantId, status, now.minus(thresholdFor.apply(status))).stream())
                .toList();
    }
}
