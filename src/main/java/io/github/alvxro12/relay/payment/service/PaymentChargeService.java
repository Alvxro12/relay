package io.github.alvxro12.relay.payment.service;

import io.github.alvxro12.relay.payment.ChargeRequestedEvent;
import io.github.alvxro12.relay.payment.PaymentStatus;
import io.github.alvxro12.relay.provider.ChargeRequest;
import io.github.alvxro12.relay.provider.ChargeResult;
import io.github.alvxro12.relay.provider.ChargeStatus;
import io.github.alvxro12.relay.provider.PaymentProvider;
import io.github.alvxro12.relay.provider.PaymentProviderTimeoutException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
public class PaymentChargeService {

    private final PaymentChargeTransactionService chargeTransactionService;
    private final PaymentProvider paymentProvider;

    public PaymentChargeService(PaymentChargeTransactionService chargeTransactionService,
                                PaymentProvider paymentProvider) {
        this.chargeTransactionService = chargeTransactionService;
        this.paymentProvider = paymentProvider;
    }

    /**
     * Orquestador sin transacción propia: claim, cobro, resultado.
     *
     * <p>El método no es {@code @Transactional} a propósito. La llamada al proveedor
     * es una llamada de red a un tercero, sin cota de tiempo real, y meterla dentro
     * de la transacción significaría sostener durante todo ese rato un lock sobre la
     * fila del pago y una conexión del pool. Con dos transacciones cortas a los lados,
     * la DB no queda tomada mientras se espera afuera.
     *
     * <p>El otro efecto es que el PROCESSING que escribe el claim está commiteado
     * antes de que arranque el cobro, así que un observador externo lo ve —y una
     * reentrega del mensaje lo lee y se va sin volver a cobrar.
     */
    @RabbitListener(queues = "payment.charge.queue")
    public void handleChargeRequested(ChargeRequestedEvent event) {
        if (!chargeTransactionService.claim(event.paymentId())) {
            return; // ack igual, sin recobrar
        }

        PaymentStatus finalStatus;
        String providerTransactionId = null;
        // Solo se captura el timeout. Cualquier otra excepción del proveedor sube sin
        // manejar a propósito: no es un catch que falte. Con el claim ya commiteado,
        // el pago queda en PROCESSING y ahí se queda hasta que reconciliación lo
        // levante para revisión manual. Un catch genérico que lo mandara a UNKNOWN o
        // lo devolviera a PENDING habilitaría que los reintentos del listener vuelvan
        // a llamar al proveedor sin saber si el cobro anterior movió plata.
        // Preferimos un pago trabado y visible a un cobro duplicado.
        try {
            ChargeResult result = paymentProvider.charge(
                    new ChargeRequest(event.amount(), event.currency())
            );
            finalStatus = mapToPaymentStatus(result.status());
            providerTransactionId = result.providerTransactionId();

        } catch (PaymentProviderTimeoutException e) {
            finalStatus = PaymentStatus.UNKNOWN;
        }

        chargeTransactionService.recordResult(event.paymentId(), finalStatus, providerTransactionId);
        // ack automático al retornar sin excepción
    }

    private PaymentStatus mapToPaymentStatus(ChargeStatus chargeStatus) {
        return switch (chargeStatus) {
            case SUCCESS -> PaymentStatus.SUCCEEDED;
            case ACCEPTED -> PaymentStatus.AWAITING_CONFIRMATION;
            case DECLINED -> PaymentStatus.FAILED;
            case SERVER_ERROR -> PaymentStatus.UNKNOWN;
            case TIMEOUT -> PaymentStatus.UNKNOWN; // no debería llegar acá (es excepción), defensivo
        };
    }
}
