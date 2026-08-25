package io.github.alvxro12.relay.payment.service;

import io.github.alvxro12.relay.payment.Payment;
import io.github.alvxro12.relay.payment.PaymentRepository;
import io.github.alvxro12.relay.payment.PaymentStatus;
import io.github.alvxro12.relay.payment.ReconciliationProperties;
import io.github.alvxro12.relay.payment.StalePaymentThresholds;
import io.github.alvxro12.relay.provider.PaymentProvider;
import io.github.alvxro12.relay.provider.ProviderStatusResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Resuelve los pagos que quedaron colgados preguntándole al proveedor qué pasó.
 *
 * <p><b>Pregunta, no reintenta.</b> Es la distinción que sostiene el gate entero. Un pago
 * en PROCESSING tiene una llamada al proveedor que puede haber movido plata: republicar el
 * evento de charge sería cobrar de nuevo sin saberlo. {@code getPaymentStatus()} no mueve
 * plata, así que se puede llamar tantas veces como haga falta sin arriesgar un cobro doble.
 * De ahí que este servicio no tenga ninguna referencia a {@code charge()} y que haya un test
 * que lo verifique contando llamadas al proveedor.
 *
 * <p>Mira los tres estados que pueden colgarse —{@code PROCESSING}, {@code UNKNOWN} y
 * {@code AWAITING_CONFIRMATION}—, cada uno con su propio umbral (ver
 * {@link StalePaymentThresholds}), y de cada pago hace una consulta y a lo sumo una
 * escritura corta. Lo que no se resuelve queda en el WARN y en
 * {@code GET /v1/payments/needs-review}, que es lo que acá significa revisión manual.
 *
 * <p>El disparo periódico no está acá sino en {@link PaymentReconciliationJob}, que es un
 * bean aparte y apagable. Este servicio se invoca directo, que es como lo llaman los tests:
 * una pasada de reconciliación tiene que poder ejecutarse cuando el test lo decide, no
 * cuando venza un temporizador.
 */
@Service
public class StalePaymentResolver {

    private static final Logger log = LoggerFactory.getLogger(StalePaymentResolver.class);

    /** Tope de pagos enumerados por WARN; el resto se resume en un conteo. */
    private static final int MAX_PAYMENTS_LISTED = 20;

    private final PaymentRepository paymentRepository;
    private final PaymentProvider paymentProvider;
    private final PaymentResolutionTransactionService resolutionService;
    private final StalePaymentThresholds thresholds;
    private final Duration giveUpAfter;

    public StalePaymentResolver(PaymentRepository paymentRepository,
                                PaymentProvider paymentProvider,
                                PaymentResolutionTransactionService resolutionService,
                                StalePaymentThresholds thresholds,
                                ReconciliationProperties reconciliationProperties) {
        this.paymentRepository = paymentRepository;
        this.paymentProvider = paymentProvider;
        this.resolutionService = resolutionService;
        this.thresholds = thresholds;
        this.giveUpAfter = reconciliationProperties.giveUpAfter();
    }

    /** Qué pasó con un pago colgado en esta pasada. */
    private enum Outcome {
        /** Se resolvió: salió del estado en el que estaba colgado. */
        RESOLVED,
        /** Se preguntó y el proveedor no alcanzó a resolverlo. Se vuelve a intentar. */
        STILL_ASKING,
        /** Se agotó el plazo: no se pregunta más y queda para una persona. */
        GAVE_UP
    }

    /**
     * Una pasada completa: por cada estado revisable, consulta el proveedor por los pagos
     * vencidos y reporta lo que quedó sin resolver.
     *
     * <p>Un WARN por estado y no uno solo con todo junto: cada estado abre una investigación
     * distinta, y quien lee el log necesita saber cuál sin ir a mirar la tabla. Y dentro de
     * cada estado, los abandonados van separados de los que se siguen consultando, porque
     * son dos situaciones distintas: uno espera a una persona y el otro todavía no.
     */
    public void reconcileStalePayments() {
        for (PaymentStatus status : StalePaymentThresholds.REVIEWABLE_STATUSES) {
            List<Payment> stale = findStalePayments(status);
            if (stale.isEmpty()) {
                continue;
            }

            List<Payment> stillAsking = new ArrayList<>();
            List<Payment> gaveUp = new ArrayList<>();
            for (Payment payment : stale) {
                switch (resolve(payment)) {
                    case RESOLVED -> { /* ya no está colgado; no hay nada que reportar */ }
                    case STILL_ASKING -> stillAsking.add(payment);
                    case GAVE_UP -> gaveUp.add(payment);
                }
            }

            if (!stillAsking.isEmpty()) {
                log.warn("Reconciliación: {} pago(s) en {} sin resolver hace más de {}. "
                                + "El proveedor todavía no los resuelve; se sigue consultando. {} Pagos: {}",
                        stillAsking.size(), status, thresholds.forStatus(status),
                        investigationHint(status), describe(stillAsking));
            }
            if (!gaveUp.isEmpty()) {
                log.warn("Reconciliación: {} pago(s) en {} llevan más de {} desde el intento de cobro "
                                + "y ya no se consultan. Necesitan revisión manual. {} Pagos: {}",
                        gaveUp.size(), status, giveUpAfter, investigationHint(status), describe(gaveUp));
            }
        }
    }

    /**
     * Consulta el proveedor por un pago y aplica lo que conteste.
     *
     * <p>El {@code catch} de {@link OptimisticLockingFailureException} va acá, fuera de la
     * transacción, y no adentro de {@link PaymentResolutionTransactionService}: el conflicto
     * de {@code @Version} aparece cuando se escribe la fila —en el flush o en el commit—, y
     * en los dos casos la transacción ya está marcada para rollback. Atraparlo adentro sería
     * seguir trabajando sobre una transacción condenada. Con el umbral de PROCESSING en dos
     * minutos,
     * este servicio y el consumer de charge escriben la misma fila de rutina, así que perder
     * la carrera es normal y no es un error: el pago se vuelve a mirar en el ciclo
     * siguiente, releyendo un estado que para entonces probablemente ya esté resuelto.
     */
    private Outcome resolve(Payment payment) {
        Instant now = Instant.now();

        if (hasGivenUp(payment, now)) {
            return Outcome.GAVE_UP;
        }

        ProviderStatusResult inquiry;
        try {
            inquiry = paymentProvider.getPaymentStatus(payment.getId());
        } catch (RuntimeException e) {
            // La consulta también es una llamada de red y se puede caer. Que un pago no se
            // pueda consultar no puede abortar la pasada de los demás.
            log.warn("Reconciliación: falló la consulta de estado del pago {}. Se reintenta "
                    + "en el ciclo siguiente.", payment.getId(), e);
            return Outcome.STILL_ASKING;
        }

        try {
            return resolutionService.applyInquiry(payment.getId(), inquiry, now)
                    ? Outcome.RESOLVED
                    : Outcome.STILL_ASKING;
        } catch (OptimisticLockingFailureException e) {
            log.info("Reconciliación: el pago {} lo escribió otro mientras consultábamos. "
                    + "Se vuelve a mirar en el ciclo siguiente.", payment.getId());
            return Outcome.STILL_ASKING;
        }
    }

    /**
     * Si ya se acabó el plazo para seguir preguntando por este pago.
     *
     * <p>Es una función pura de {@code charge_attempted_at}, que se escribe una sola vez y no
     * se mueve. Rendirse no deja marca en la fila a propósito: un contador de intentos o un
     * flag serían una escritura por consulta, {@code updated_at} se movería sola y el pago
     * saldría de su propia ventana de staleness justo por haber sido mirado.
     *
     * <p>Sin la marca no hay reloj. Un pago sin {@code charge_attempted_at} —una fila
     * anterior a la migración que agregó la columna— no puede medir ni esta ventana ni la de
     * gracia del NOT_FOUND, que es la única respuesta capaz de resolverlo por la negativa.
     * Consultarlo sería consultarlo para siempre, que es exactamente el limbo que este plazo
     * existe para cerrar. Va derecho a revisión manual.
     */
    private boolean hasGivenUp(Payment payment, Instant now) {
        Instant attemptedAt = payment.getChargeAttemptedAt();
        if (attemptedAt == null) {
            return true;
        }
        return !now.isBefore(attemptedAt.plus(giveUpAfter));
    }

    /**
     * Qué mirar cuando aparece uno de estos, que es distinto en cada estado y es la razón por
     * la que el WARN los separa. Todos describen un pago que la consulta al proveedor no
     * alcanzó a resolver: son los casos que quedan para una persona.
     */
    private static String investigationHint(PaymentStatus status) {
        return switch (status) {
            case UNKNOWN -> "El cobro terminó y el proveedor tampoco sabe cómo: contesta UNKNOWN "
                    + "sobre un cobro que tiene registrado. La búsqueda arranca por externalReference "
                    + "y monto contra el extracto del proveedor.";
            case PROCESSING -> "El consumer tomó el cobro y nunca registró el resultado, así que la "
                    + "llamada al proveedor pudo haber movido plata o no haber salido nunca. Que "
                    + "siga acá después de consultarlo significa que el proveedor contestó UNKNOWN, "
                    + "o NOT_FOUND todavía dentro de la ventana de gracia. No se recobra "
                    + "automáticamente por ninguna vía.";
            case AWAITING_CONFIRMATION -> "El proveedor aceptó el cobro y lo sigue teniendo como "
                    + "pendiente, así que la investigación es sobre la demora del desenlace y la "
                    + "entrega del webhook de confirmación, no sobre el cobro.";
            default -> "";
        };
    }

    /**
     * Se lista el providerTransactionId cuando está porque es la clave con la que se busca el
     * cobro del lado del proveedor, y su ausencia es en sí misma parte del diagnóstico.
     *
     * <p>La lista está acotada: el WARN existe para que una persona lo lea, y un backlog de
     * cientos de pagos colgados haría una línea de log ilegible justo cuando más importa. El
     * conteo completo ya va al principio del mensaje, y el listado entero sale por
     * {@code GET /v1/payments/needs-review}.
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
    public List<Payment> findStalePayments() {
        return StalePaymentThresholds.REVIEWABLE_STATUSES.stream()
                .flatMap(status -> findStalePayments(status).stream())
                .toList();
    }

    public List<Payment> findStalePayments(PaymentStatus status) {
        Instant threshold = Instant.now().minus(thresholds.forStatus(status));
        // Consulta sin scope de merchant a propósito: la reconciliación mira todo el sistema.
        // La del endpoint es otra, con scope, y no se unifican (ver PaymentService).
        return paymentRepository.findByStatusAndUpdatedAtBefore(status, threshold);
    }
}
