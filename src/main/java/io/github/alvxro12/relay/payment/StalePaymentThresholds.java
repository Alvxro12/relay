package io.github.alvxro12.relay.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Cuánto puede quedarse un pago en cada estado sin resolver antes de contar como colgado.
 *
 * <p>Un umbral por estado y no uno global: los tres estados que se pueden colgar tardan
 * cosas distintas por diseño. {@code PROCESSING} dura lo que dura una llamada al
 * proveedor, o sea segundos; si lleva minutos, el consumer murió. {@code UNKNOWN} arranca
 * ya resuelto —el cobro terminó, lo que no sabemos es cómo—, así que el umbral es el
 * tiempo que se le da a una reconciliación antes de escalar. {@code AWAITING_CONFIRMATION}
 * espera un webhook del proveedor, que puede tardar minutos u horas legítimamente: alertar
 * con el umbral de los otros dos sería alertar sobre el flujo feliz, y una alerta que
 * suena cuando no pasa nada deja de mirarse.
 */
@ConfigurationProperties(prefix = "relay.reconciliation.stale-after")
public record StalePaymentThresholds(
        @DefaultValue("15m") Duration unknown,
        @DefaultValue("2m") Duration processing,
        @DefaultValue("6h") Duration awaitingConfirmation
) {

    /**
     * Los estados que pueden quedar colgados, en el orden en que se reportan. PENDING no
     * está porque todavía no lo tomó nadie y el consumer de charge lo va a levantar;
     * SUCCEEDED y FAILED son terminales y no hay nada que revisar.
     */
    public static final List<PaymentStatus> REVIEWABLE_STATUSES = List.of(
            PaymentStatus.UNKNOWN,
            PaymentStatus.PROCESSING,
            PaymentStatus.AWAITING_CONFIRMATION);

    public Duration forStatus(PaymentStatus status) {
        return switch (status) {
            case UNKNOWN -> unknown;
            case PROCESSING -> processing;
            case AWAITING_CONFIRMATION -> awaitingConfirmation;
            case PENDING, SUCCEEDED, FAILED -> throw new IllegalArgumentException(
                    "No hay umbral de revisión para " + status + ": no es un estado que quede colgado");
        };
    }
}
