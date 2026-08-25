package io.github.alvxro12.relay.payment;

import java.util.UUID;

/**
 * @param externalReference etiqueta del merchant, propagada hasta el proveedor. Nullable:
 *                          es opcional en el request y, ademas, un mensaje publicado por
 *                          una version anterior de Relay no la trae. En ese caso el
 *                          proveedor simulado se comporta como siempre (escenario exitoso).
 */
public record ChargeRequestedEvent(
        UUID paymentId,
        UUID merchantId,
        Long amount,
        String currency,
        String externalReference
) {
}
