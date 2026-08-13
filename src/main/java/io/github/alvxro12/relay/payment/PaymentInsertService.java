package io.github.alvxro12.relay.payment;

import io.github.alvxro12.relay.payment.dto.CreatePaymentRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

@Service
public class PaymentInsertService {

    private final PaymentRepository paymentRepository;
    private final PaymentEventPublisher eventPublisher;


    public PaymentInsertService(PaymentRepository paymentRepository, PaymentEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.eventPublisher = eventPublisher;
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

        Payment saved = paymentRepository.saveAndFlush(payment);

        // Publicar recién cuando el commit sea efectivo: si se publica antes,
        // el listener puede recibir el evento y consultar la fila antes de
        // que sea visible fuera de esta transacción.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishChargeRequested(
                        new ChargeRequestedEvent(saved.getId(), saved.getMerchantId(), saved.getAmount(), saved.getCurrency())
                );
            }
        });

        return saved;
    }
}
