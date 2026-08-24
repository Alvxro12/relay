package io.github.alvxro12.relay.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Escenario de falla que el proveedor simulado va a ejecutar, elegido por el
 * {@code externalReference} del pago.
 *
 * <p><b>Por que la referencia y no el monto.</b> El monto tiene significado de negocio y
 * hay que dejarlo libre: el contrato publico valida topes, {@code amount > 0} y exponente
 * por moneda, y con montos magicos no se puede probar el borde de un monto que ademas
 * significa "declinar". Con la referencia, monto y escenario son ejes independientes: se
 * puede probar cualquier escenario con cualquier monto, en cualquier moneda. Y un monto
 * magico se dispara solo —un merchant cobra 10.02 con datos realistas y le declinan sin
 * haberlo pedido—, mientras que {@code RELAY_TEST_} no se teclea por accidente.
 *
 * <p>Formato: la referencia arranca con {@code RELAY_TEST_}, sigue el nombre del escenario
 * y, opcionalmente, {@code :} mas lo que el merchant quiera para no perder su propia
 * correlacion.
 *
 * <pre>
 *   RELAY_TEST_FAILED
 *   RELAY_TEST_LOST_RESPONSE:orden-4471
 * </pre>
 */
public enum SandboxScenario {

    /** Cobrado y resuelto en la misma llamada. Es tambien el comportamiento por defecto. */
    SUCCESS,

    /** El proveedor rechazo el cobro. Es un resultado, no una falla. */
    FAILED,

    /** El proveedor se hizo cargo; el desenlace llega despues por webhook. */
    AWAITING_CONFIRMATION,

    /**
     * La llamada corta por timeout y <b>el cobro nunca ocurrio</b>: no queda nada del lado
     * del proveedor.
     */
    TIMEOUT,

    /**
     * El caso que justifica el gate entero: <b>el cobro ocurrio y salio bien</b>, pero la
     * respuesta se perdio en el camino. Desde Relay se ve identico a TIMEOUT.
     */
    LOST_RESPONSE,

    /** El proveedor registro algo pero el mismo no sabe como termino. */
    UNKNOWN;

    private static final Logger log = LoggerFactory.getLogger(SandboxScenario.class);

    public static final String PREFIX = "RELAY_TEST_";

    /** Todo lo que venga despues de esto es del merchant y se ignora. */
    private static final char SUFFIX_SEPARATOR = ':';

    /**
     * Resuelve el escenario de una referencia.
     *
     * <p>Sin prefijo devuelve SUCCESS, que es lo que corresponde: la enorme mayoria de las
     * referencias son etiquetas normales de un merchant y no tienen por que significar nada.
     *
     * <p>Con prefijo pero con un nombre que no existe tambien devuelve SUCCESS, y ademas
     * loguea. Es un error de quien integra —se equivoco escribiendo el escenario— y la
     * alternativa, fallar el cobro, convertiria un typo en un pago roto.
     */
    public static SandboxScenario from(String externalReference) {
        if (externalReference == null) {
            return SUCCESS;
        }

        String trimmed = externalReference.trim();
        if (trimmed.length() <= PREFIX.length()
                || !trimmed.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return SUCCESS;
        }

        String token = trimmed.substring(PREFIX.length());
        int suffixAt = token.indexOf(SUFFIX_SEPARATOR);
        if (suffixAt >= 0) {
            token = token.substring(0, suffixAt);
        }

        try {
            return valueOf(token.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Referencia de sandbox no reconocida: '{}'. Escenarios validos: {}{}. "
                            + "Se cobra normalmente.",
                    externalReference, PREFIX, java.util.Arrays.toString(values()));
            return SUCCESS;
        }
    }
}
