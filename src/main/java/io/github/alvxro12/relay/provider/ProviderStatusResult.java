package io.github.alvxro12.relay.provider;

/**
 * Respuesta de una consulta de estado.
 *
 * @param providerTransactionId el id de la transaccion cuando el proveedor lo tiene. Es lo
 *                              que hace util a la consulta en el caso de la respuesta
 *                              perdida: el cobro existe y recien ahi Relay se entera de
 *                              con que clave quedo registrado. Null en NOT_FOUND, y puede
 *                              serlo en UNKNOWN.
 */
public record ProviderStatusResult(
        ProviderPaymentStatus status,
        String providerTransactionId
) {
}
