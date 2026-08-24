package io.github.alvxro12.relay.provider;

import java.util.UUID;

/**
 * Lo que Relay le manda al proveedor para cobrar.
 *
 * @param paymentId         id del pago en Relay. Va como clave de idempotencia y de
 *                          correlacion del lado del proveedor, y es <b>la unica clave con
 *                          la que despues se puede consultar el resultado</b>: cuando
 *                          {@code charge()} corta por timeout no hay
 *                          {@code providerTransactionId}, y ese es justamente el caso que
 *                          hay que poder resolver. Ver {@link PaymentProvider#getPaymentStatus}.
 * @param amount            en la unidad minima de la moneda.
 * @param currency          ISO 4217.
 * @param externalReference etiqueta del merchant. Un proveedor real la recibe y la imprime
 *                          en el extracto; en el proveedor simulado ademas selecciona el
 *                          escenario de falla (ver {@code SandboxScenario}).
 */
public record ChargeRequest(
        UUID paymentId,
        Long amount,
        String currency,
        String externalReference
) {
}
