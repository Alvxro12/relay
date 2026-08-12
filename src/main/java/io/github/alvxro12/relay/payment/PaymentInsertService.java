package io.github.alvxro12.relay.payment;

import io.github.alvxro12.relay.payment.dto.CreatePaymentRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PaymentInsertService {

    private final PaymentRepository paymentRepository;

    public PaymentInsertService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment insert(UUID merchantId, String idempotencyKey, CreatePaymentRequest request) {
        Payment payment = new Payment();
        payment.setMerchantId(merchantId);
        payment.setIdempotencyKey(idempotencyKey);
        payment.setAmount(request.amount());
        payment.setCurrency(request.currency());
        payment.setReference(request.reference());
        payment.setStatus(PaymentStatus.PENDING);

        return paymentRepository.saveAndFlush(payment);
    }
}
