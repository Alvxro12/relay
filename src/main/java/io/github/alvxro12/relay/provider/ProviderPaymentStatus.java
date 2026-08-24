package io.github.alvxro12.relay.provider;

/**
 * Lo que el proveedor dice que paso con un cobro cuando se le pregunta.
 *
 * <p>No es {@link ChargeStatus} y no hay que unificarlos: {@code ChargeStatus} es el
 * desenlace de <em>una llamada</em>, esto es el estado de <em>un registro</em>. TIMEOUT no
 * significa nada al consultar, y NOT_FOUND no significa nada al cobrar.
 */
public enum ProviderPaymentStatus {

    /** El cobro ocurrio y salio bien. */
    SUCCEEDED,

    /** El cobro ocurrio y fue rechazado. */
    FAILED,

    /** El proveedor lo tiene tomado y todavia no lo resolvio. Preguntar mas tarde. */
    PENDING,

    /**
     * El proveedor tiene el cobro registrado pero no sabe como termino.
     * <b>No habilita ninguna decision:</b> el pago queda para revision manual.
     */
    UNKNOWN,

    /**
     * No existe ningun cobro con esa clave: la llamada nunca llego. Es la unica respuesta
     * que autoriza a dar el pago por fallido, y por eso esta separada de UNKNOWN.
     */
    NOT_FOUND
}
