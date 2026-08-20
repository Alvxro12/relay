package io.github.alvxro12.relay.payment;

public enum PaymentStatus {
    /** Creado, todavía no se intentó cobrar. */
    PENDING,
    /** Tomado por un consumer de charge, con el cobro en vuelo. */
    PROCESSING,
    /**
     * El proveedor aceptó el cobro y devolvió un id de transacción, pero todavía no
     * lo resolvió: la confirmación llega después, por webhook.
     *
     * <p>Es un estado propio y no un PENDING porque significa lo contrario: PENDING
     * es "acá nadie cobró nada todavía" y este es "el cobro está en manos del
     * proveedor". Confundirlos haría que la guarda del claim, que es {@code status ==
     * PENDING}, volviera a cobrar un pago que el proveedor ya aceptó.
     */
    AWAITING_CONFIRMATION,
    SUCCEEDED,
    FAILED,
    /** No sabemos si el proveedor cobró o no. Ver README, sección "Estado UNKNOWN". */
    UNKNOWN
}
