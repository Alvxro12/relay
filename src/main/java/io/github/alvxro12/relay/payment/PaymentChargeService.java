package io.github.alvxro12.relay.payment;

import io.github.alvxro12.relay.provider.ChargeRequest;
import io.github.alvxro12.relay.provider.ChargeResult;
import io.github.alvxro12.relay.provider.ChargeStatus;
import io.github.alvxro12.relay.provider.PaymentProvider;
import io.github.alvxro12.relay.provider.PaymentProviderTimeoutException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PaymentChargeService {

    private final PaymentRepository paymentRepository;
    private final PaymentProvider paymentProvider;

    public PaymentChargeService(PaymentRepository paymentRepository, PaymentProvider paymentProvider) {
        this.paymentRepository = paymentRepository;
        this.paymentProvider = paymentProvider;
    }

    @RabbitListener(queues = "payment.charge.queue")
    @Transactional
    public void handleChargeRequested(ChargeRequestedEvent event) {
        Payment payment = paymentRepository.findById(event.paymentId())
                .orElseThrow(() -> new IllegalStateException(
                        "Payment not found for charge event: " + event.paymentId()));

        // Idempotencia del consumer: si ya se procesó, no reprocesar
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return; // ack igual, sin recobrar
        }

        payment.setStatus(PaymentStatus.PROCESSING);
        paymentRepository.saveAndFlush(payment);

        PaymentStatus finalStatus;
        try {
            ChargeResult result = paymentProvider.charge(
                    new ChargeRequest(event.amount(), event.currency())
            );
            finalStatus = mapToPaymentStatus(result.status());

        } catch (PaymentProviderTimeoutException e) {
            finalStatus = PaymentStatus.UNKNOWN;
        }

        payment.setStatus(finalStatus);
        paymentRepository.saveAndFlush(payment);
        // ack automático al retornar sin excepción
    }

    private PaymentStatus mapToPaymentStatus(ChargeStatus chargeStatus) {
        return switch (chargeStatus) {
            case SUCCESS -> PaymentStatus.SUCCEEDED;
            case DECLINED -> PaymentStatus.FAILED;
            case SERVER_ERROR -> PaymentStatus.UNKNOWN;
            case TIMEOUT -> PaymentStatus.UNKNOWN; // no debería llegar acá (es excepción), defensivo
        };
    }
}