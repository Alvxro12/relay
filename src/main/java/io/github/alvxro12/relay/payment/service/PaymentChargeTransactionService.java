package io.github.alvxro12.relay.payment.service;

import io.github.alvxro12.relay.payment.Payment;
import io.github.alvxro12.relay.payment.PaymentRepository;
import io.github.alvxro12.relay.payment.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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

    private static final Logger log = LoggerFactory.getLogger(PaymentChargeTransactionService.class);

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
        // Marca desde la que se mide la ventana de gracia de NOT_FOUND. Se escribe una
        // sola vez; ver Payment.markChargeAttempted.
        payment.markChargeAttempted(Instant.now());
        paymentRepository.saveAndFlush(payment);
        return true;
    }

    /**
     * Persiste el desenlace del cobro, ya con el proveedor fuera de juego.
     *
     * <p><b>El status no se escribe si el pago ya está en un estado terminal.</b> Entre
     * el claim y esta llamada pasa la llamada al proveedor entera, con la fila
     * commiteada en PROCESSING y sin lock: cualquiera puede escribirla mientras tanto.
     * {@code @Version} no cubre esa ventana —esta transacción carga la entidad fresca y
     * la escribe en el acto, así que no hay conflicto de versión que detectar—, de modo
     * que la única protección posible es releer el estado y decidir, igual que hace
     * {@code WebhookProcessingService}. Sin la guarda, un webhook que resolvió el pago
     * quedaría pisado en silencio.
     *
     * <p>Hoy el escenario no es alcanzable, y por un motivo indirecto: el webhook
     * correlaciona por {@code providerTransactionId} y ese campo recién se escribe acá,
     * así que mientras el cobro está en vuelo el webhook no encuentra el pago. Eso es
     * una ventana de tiempo, no una guarda; deja de valer apenas el id del proveedor se
     * conozca antes (correlación por otra clave, resolución manual, un provider que lo
     * devuelva al iniciar el cobro). La guarda es lo que hace que el invariante no
     * dependa de ese accidente.
     *
     * <p><b>El {@code providerTransactionId} sí se escribe aunque el status se descarte</b>,
     * y solo cuando el pago todavía no tiene uno. Son dos datos con dueños distintos: el
     * status es el desenlace —y del desenlace manda quien llegó primero a terminal—, pero
     * el id de transacción es la única clave con la que el pago se correlaciona contra el
     * proveedor. Un pago que llegó a terminal por otra vía antes de que el id estuviera
     * disponible quedaría permanentemente sin correlación: sin nada con qué atarlo a un
     * webhook posterior, a una disputa o a una conciliación manual. Tirar ese dato para
     * respetar una guarda que es sobre el status sería perder información sin ganar nada.
     * No se pisa uno existente por el mismo motivo por el que no se pisa el status: si ya
     * hay un id guardado, es el que alguien usó para correlacionar.
     *
     * <p>El {@code providerTransactionId} tampoco se escribe si vino en null: un TIMEOUT
     * o un SERVER_ERROR no traen ninguno, y pisar con null el que ya está guardado
     * dejaría el pago sin esa misma clave.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordResult(UUID paymentId, PaymentStatus finalStatus, String providerTransactionId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Payment not found when recording charge result: " + paymentId));

        boolean correlationRecovered = providerTransactionId != null
                && payment.getProviderTransactionId() == null;
        if (correlationRecovered) {
            payment.setProviderTransactionId(providerTransactionId);
        }

        if (isTerminal(payment.getStatus())) {
            log.warn("Resultado del cobro no aplicado: el pago {} ya está en {} y el cobro intentaba "
                            + "dejarlo en {}. No se pisa un estado terminal.",
                    paymentId, payment.getStatus(), finalStatus);
            if (correlationRecovered) {
                log.warn("Pago {}: se persiste igual el providerTransactionId {}, que faltaba, "
                                + "para no dejarlo sin correlación con el proveedor.",
                        paymentId, providerTransactionId);
            }
            paymentRepository.saveAndFlush(payment);
            return;
        }

        payment.setStatus(finalStatus);
        paymentRepository.saveAndFlush(payment);
    }

    /**
     * Terminal es solo SUCCEEDED o FAILED, el mismo criterio que usa
     * {@code WebhookProcessingService}. PROCESSING, AWAITING_CONFIRMATION y UNKNOWN
     * quedan afuera: son justamente los estados desde los que este método resuelve.
     */
    private boolean isTerminal(PaymentStatus status) {
        return status == PaymentStatus.SUCCEEDED || status == PaymentStatus.FAILED;
    }
}
