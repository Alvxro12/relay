package io.github.alvxro12.relay.payment.service;

import io.github.alvxro12.relay.payment.Payment;
import io.github.alvxro12.relay.payment.PaymentRepository;
import io.github.alvxro12.relay.payment.PaymentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Las dos transacciones cortas del cobro, a los lados de la llamada al proveedor.
 *
 * <p>Vive en un bean aparte de {@link PaymentChargeService} a propósito:
 * {@code REQUIRES_NEW} lo aplica el proxy de Spring, y una llamada desde el propio
 * listener a un método suyo no pasa por el proxy, así que la anotación no tendría
 * ningún efecto y todo volvería a correr en una sola transacción.
 */
@Service
public class PaymentChargeTransactionService {

    private final PaymentRepository paymentRepository;

    public PaymentChargeTransactionService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    /**
     * Toma el pago para cobrarlo: lo pasa de PENDING a PROCESSING y commitea.
     *
     * <p>El commit es el punto de todo. Deja el PROCESSING visible para cualquier
     * otro lector antes de que empiece la llamada al proveedor, y con eso la fila
     * pasa a ser la guarda real contra reentregas: una segunda entrega del mismo
     * evento relee un estado que ya no es PENDING y no vuelve a cobrar.
     *
     * <p>Es también la razón por la que releemos acá en vez de confiar en el evento:
     * si el consumer de webhook ganó la carrera y dejó el pago en un estado terminal,
     * salimos por el mismo camino sin pisar ese resultado.
     *
     * @return true si este consumer se quedó con el cobro; false si no hay nada que
     *         hacer y el mensaje se puede ackear.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Payment not found for charge event: " + paymentId));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            return false;
        }

        payment.setStatus(PaymentStatus.PROCESSING);
        paymentRepository.saveAndFlush(payment);
        return true;
    }

    /**
     * Persiste el desenlace del cobro, ya con el proveedor fuera de juego.
     *
     * <p>El {@code providerTransactionId} solo se escribe si vino: un TIMEOUT o un
     * SERVER_ERROR no traen ninguno, y pisar con null el que ya está guardado
     * dejaría el pago sin la única clave con la que un webhook puede correlacionarlo.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordResult(UUID paymentId, PaymentStatus finalStatus, String providerTransactionId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Payment not found when recording charge result: " + paymentId));

        if (providerTransactionId != null) {
            payment.setProviderTransactionId(providerTransactionId);
        }
        payment.setStatus(finalStatus);
        paymentRepository.saveAndFlush(payment);
    }
}
