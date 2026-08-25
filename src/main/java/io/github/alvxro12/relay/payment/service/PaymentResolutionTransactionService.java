package io.github.alvxro12.relay.payment.service;

import io.github.alvxro12.relay.payment.NotFoundGracePolicy;
import io.github.alvxro12.relay.payment.Payment;
import io.github.alvxro12.relay.payment.PaymentRepository;
import io.github.alvxro12.relay.payment.PaymentStatus;
import io.github.alvxro12.relay.provider.ProviderStatusResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * La transacción corta que escribe lo que la consulta al proveedor dejó decidido.
 *
 * <p>Vive aparte de {@link StalePaymentResolver} por el mismo motivo que
 * {@link PaymentChargeTransactionService} vive aparte de {@link PaymentChargeService}:
 * {@code REQUIRES_NEW} lo aplica el proxy de Spring, y una llamada del resolver a un método
 * suyo no pasaría por el proxy. Pero además, y sobre todo, porque la consulta al proveedor
 * es una llamada de red a un tercero y no puede quedar adentro de la transacción: sería
 * sostener un lock sobre la fila y una conexión del pool durante todo ese rato, exactamente
 * lo que el claim-then-call evita del lado del cobro.
 *
 * <p>De ahí que el pago se relea acá adentro en vez de recibirlo ya cargado. Entre que el
 * resolver lo listó y esta transacción escribe pasó la consulta entera, y en el medio el
 * consumer de charge o el de webhook pudieron resolverlo. Lo que se lee acá es lo que hay
 * ahora, no lo que había cuando se armó la lista.
 */
@Service
public class PaymentResolutionTransactionService {

    private static final Logger log = LoggerFactory.getLogger(PaymentResolutionTransactionService.class);

    private final PaymentRepository paymentRepository;
    private final NotFoundGracePolicy notFoundGracePolicy;

    public PaymentResolutionTransactionService(PaymentRepository paymentRepository,
                                               NotFoundGracePolicy notFoundGracePolicy) {
        this.paymentRepository = paymentRepository;
        this.notFoundGracePolicy = notFoundGracePolicy;
    }

    /**
     * Aplica al pago lo que el proveedor contestó.
     *
     * <p><b>No escribe cuando la consulta no resuelve nada.</b> No es una optimización: el
     * staleness se mide con {@code updated_at}, así que cualquier escritura inútil sacaría
     * al pago de su propia ventana y la reconciliación no lo volvería a mirar hasta que
     * envejeciera de nuevo. Un UNKNOWN consultado cada cinco minutos y tocado cada vez no
     * se consultaría nunca más.
     *
     * @return true si el pago salió del estado en el que estaba colgado. Un false no es un
     *         error: es "el proveedor todavía no sabe" o "todavía no le creemos", y el pago
     *         se vuelve a mirar en el ciclo siguiente.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean applyInquiry(UUID paymentId, ProviderStatusResult inquiry, Instant now) {
        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) {
            // Se listó hace un momento, así que no debería pasar. No se lanza: en una pasada
            // por lotes, una fila rara no puede abortar las demás.
            log.warn("Reconciliación: el pago {} desapareció entre el listado y la escritura", paymentId);
            return false;
        }

        PaymentStatus current = payment.getStatus();

        // El id de transacción se rescata aunque el estado no se toque, y solo si el pago
        // todavía no tiene uno. Misma regla que recordResult y por el mismo motivo: es la
        // única clave con la que este pago se ata a un webhook posterior, a una disputa o a
        // una conciliación manual. En el caso que justifica el gate —el cobro salió y la
        // respuesta se perdió— esta consulta es la primera vez que Relay se entera de con
        // qué id quedó registrado. No se pisa uno existente, porque si ya hay uno guardado
        // es el que alguien usó para correlacionar; y no se pisa con null, porque un
        // NOT_FOUND no trae ninguno.
        boolean correlationRecovered = inquiry.providerTransactionId() != null
                && payment.getProviderTransactionId() == null;

        if (isTerminal(current)) {
            // Alguien llegó primero mientras preguntábamos: un webhook, o el recordResult de
            // un cobro que resultó no estar tan muerto. Del desenlace manda quien llegó
            // primero a terminal; acá solo se rescata la correlación si faltaba.
            if (correlationRecovered) {
                payment.setProviderTransactionId(inquiry.providerTransactionId());
                paymentRepository.saveAndFlush(payment);
                log.info("Pago {}: ya estaba en {} cuando volvió la consulta. No se pisa el estado; "
                                + "se persiste el providerTransactionId {}, que faltaba.",
                        paymentId, current, inquiry.providerTransactionId());
            }
            return true;
        }

        if (current == PaymentStatus.PENDING) {
            // Volver a PENDING no es una transición que exista hoy. Si alguna vez existiera,
            // este pago le pertenece al consumer de charge y no a la reconciliación.
            return false;
        }

        PaymentStatus target = decide(inquiry, payment, now);

        if (target == null && !correlationRecovered) {
            return false; // nada que escribir; ver el javadoc del método
        }

        if (correlationRecovered) {
            payment.setProviderTransactionId(inquiry.providerTransactionId());
        }
        if (target != null) {
            payment.setStatus(target);
        }
        paymentRepository.saveAndFlush(payment);

        if (target != null && target != current) {
            log.info("Reconciliación: el pago {} pasó de {} a {} porque el proveedor contestó {}.",
                    paymentId, current, target, inquiry.status());
            return true;
        }
        return false;
    }

    /**
     * Qué estado le corresponde al pago según lo que contestó el proveedor. Null significa
     * "no tocarlo".
     */
    private PaymentStatus decide(ProviderStatusResult inquiry, Payment payment, Instant now) {
        return switch (inquiry.status()) {
            // El cobro ocurrió y el proveedor sabe cómo terminó. Es la respuesta que resuelve
            // el caso que justifica el gate entero: la plata se movió y Relay no se enteró.
            case SUCCEEDED -> PaymentStatus.SUCCEEDED;
            case FAILED -> PaymentStatus.FAILED;

            // El proveedor lo tiene tomado y todavía no lo resolvió, o sea que el desenlace
            // llega por webhook. AWAITING_CONFIRMATION es exactamente eso, y además mueve al
            // pago al umbral que le corresponde: un PROCESSING se mide con 2m porque
            // significa "el consumer murió", y eso ya sabemos que no fue lo que pasó.
            case PENDING -> PaymentStatus.AWAITING_CONFIRMATION;

            // El proveedor tiene el cobro registrado y no sabe cómo terminó. Es la respuesta
            // que no habilita ninguna decisión: hay plata que pudo haberse movido y ninguna
            // escritura acá es segura. Se vuelve a preguntar hasta que conteste otra cosa o
            // hasta que se agote relay.reconciliation.give-up-after.
            case UNKNOWN -> null;

            // La única respuesta que autoriza a escribir FAILED, y solo pasada la ventana de
            // gracia: un proveedor real puede contestar NOT_FOUND por un cobro que todavía
            // está creando de su lado. Ver NotFoundGracePolicy.
            case NOT_FOUND -> notFoundGracePolicy.authorizesFailing(inquiry, payment, now)
                    ? PaymentStatus.FAILED
                    : null;
        };
    }

    /** Mismo criterio que {@code PaymentChargeTransactionService} y {@code WebhookProcessingService}. */
    private boolean isTerminal(PaymentStatus status) {
        return status == PaymentStatus.SUCCEEDED || status == PaymentStatus.FAILED;
    }
}
