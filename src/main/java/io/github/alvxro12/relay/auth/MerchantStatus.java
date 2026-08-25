package io.github.alvxro12.relay.auth;

/**
 * Estado de un merchant. Solo un merchant {@code ACTIVE} puede obtener un token y
 * usarlo: el chequeo corre dos veces, al emitir y al validar, porque un token vive
 * hasta 15 minutos y un merchant puede quedar deshabilitado en el medio.
 */
public enum MerchantStatus {

    ACTIVE,

    /** Deshabilitado. No emite tokens nuevos y los que ya emitió dejan de servir. */
    DISABLED
}
