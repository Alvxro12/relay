package io.github.alvxro12.relay.provider;

public enum ChargeStatus {
    /** Cobrado y resuelto en la misma llamada. */
    SUCCESS,
    /**
     * El proveedor se hizo cargo del cobro y devolvió un id de transacción, pero el
     * resultado llega después por webhook. Es el flujo real de la mayoría de los
     * proveedores, y el único que produce un pago que un webhook puede resolver.
     */
    ACCEPTED,
    DECLINED,
    TIMEOUT,
    SERVER_ERROR
}
