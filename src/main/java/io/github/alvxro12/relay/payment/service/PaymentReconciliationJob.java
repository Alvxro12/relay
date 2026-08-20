package io.github.alvxro12.relay.payment.service;

import io.github.alvxro12.relay.payment.Payment;
import io.github.alvxro12.relay.payment.PaymentRepository;
import io.github.alvxro12.relay.payment.PaymentStatus;
import io.github.alvxro12.relay.payment.StalePaymentThresholds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Detecta pagos que quedaron colgados sin resolver y los escala a revisión manual.
 *
 * <p>Mira los tres estados que pueden colgarse —{@code UNKNOWN}, {@code PROCESSING} y
 * {@code AWAITING_CONFIRMATION}—, cada uno con su propio umbral (ver
 * {@link StalePaymentThresholds}).
 *
 * <p><b>No reintenta ni republica nada.</b> Vale para los tres, pero sobre todo para
 * {@code PROCESSING}: ese pago tiene una llamada al proveedor que puede haber movido
 * plata, y republicar el evento de charge sería cobrar de nuevo sin saberlo. Sin API de
 * consulta de estado en el proveedor (ver {@code PaymentProvider}), la única salida
 * correcta es que lo mire una persona, vía {@code GET /payments/needs-review}.
 */
@Component
public class PaymentReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);

    /** Tope de pagos enumerados por WARN; el resto se resume en un conteo. */
    private static final int MAX_PAYMENTS_LISTED = 20;

    private final PaymentRepository paymentRepository;
    private final StalePaymentThresholds thresholds;

    public PaymentReconciliationJob(PaymentRepository paymentRepository,
                                    StalePaymentThresholds thresholds) {
        this.paymentRepository = paymentRepository;
        this.thresholds = thresholds;
    }

    /**
     * Un WARN por estado, no uno solo con todo junto: cada estado abre una investigación
     * distinta, y quien lee el log necesita saber cuál sin ir a mirar la tabla.
     */
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void logStalePayments() {
        for (PaymentStatus status : StalePaymentThresholds.REVIEWABLE_STATUSES) {
            List<Payment> stale = findStalePayments(status);
            if (stale.isEmpty()) {
                continue;
            }
            log.warn("Reconciliación: {} pago(s) en {} sin resolver hace más de {}. {} Pagos: {}",
                    stale.size(), status, thresholds.forStatus(status), investigationHint(status), describe(stale));
        }
    }

    /**
     * Qué mirar cuando aparece uno de estos, que es distinto en cada estado y es la razón
     * por la que el WARN los separa.
     */
    private static String investigationHint(PaymentStatus status) {
        return switch (status) {
            case UNKNOWN -> "El cobro terminó pero no sabemos cómo, y no hay providerTransactionId "
                    + "con el cual preguntar: la búsqueda arranca por reference y monto contra el "
                    + "extracto del proveedor.";
            case PROCESSING -> "El consumer tomó el cobro y nunca registró el resultado, así que la "
                    + "llamada al proveedor pudo haber movido plata o no haber salido nunca. El "
                    + "providerTransactionId puede estar o no según dónde murió: si está, el cobro "
                    + "llegó a completarse y lo que falta es el desenlace; si no está, hay que "
                    + "buscar por reference. No se recobra automáticamente.";
            case AWAITING_CONFIRMATION -> "El proveedor aceptó el cobro y dejó providerTransactionId, "
                    + "así que la investigación es sobre la entrega del webhook de confirmación, no "
                    + "sobre el cobro.";
            default -> "";
        };
    }

    /**
     * Se lista el providerTransactionId cuando está porque es la clave con la que se le
     * pregunta al proveedor, y su ausencia es en sí misma parte del diagnóstico.
     *
     * <p>La lista está acotada: el WARN existe para que una persona lo lea, y un backlog
     * de cientos de pagos colgados haría una línea de log ilegible justo cuando más
     * importa. El conteo completo ya va al principio del mensaje, y el listado entero
     * sale por {@code GET /payments/needs-review}.
     */
    private static String describe(List<Payment> payments) {
        String listed = payments.stream()
                .limit(MAX_PAYMENTS_LISTED)
                .map(p -> p.getProviderTransactionId() == null
                        ? p.getId() + " (sin providerTransactionId)"
                        : p.getId() + " (txn " + p.getProviderTransactionId() + ")")
                .collect(Collectors.joining(", "));

        int remaining = payments.size() - MAX_PAYMENTS_LISTED;
        return remaining > 0 ? listed + ", y " + remaining + " más" : listed;
    }

    /** Los colgados de los tres estados juntos, cada uno medido contra su propio umbral. */
    List<Payment> findStalePayments() {
        return StalePaymentThresholds.REVIEWABLE_STATUSES.stream()
                .flatMap(status -> findStalePayments(status).stream())
                .toList();
    }

    List<Payment> findStalePayments(PaymentStatus status) {
        Instant threshold = Instant.now().minus(thresholds.forStatus(status));
        // Consulta sin scope de merchant a propósito: el job mira todo el sistema. La del
        // endpoint es otra, con scope, y no se unifican (ver PaymentService).
        return paymentRepository.findByStatusAndUpdatedAtBefore(status, threshold);
    }
}
