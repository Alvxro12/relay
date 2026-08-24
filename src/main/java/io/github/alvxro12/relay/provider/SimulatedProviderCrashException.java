package io.github.alvxro12.relay.provider;

/**
 * El cliente del proveedor se rompio despues de que el cobro ya se aplico, por algo que
 * no es un timeout: una conexion cortada, una respuesta que no se pudo deserializar, un
 * error del cliente HTTP.
 *
 * <p><b>Lo importante es lo que NO es.</b> No extiende PaymentProviderTimeoutException, y
 * ese es todo el punto: PaymentChargeService atrapa unicamente el timeout, asi que esto
 * sube sin manejar, recordResult nunca corre y el pago se queda en PROCESSING con el claim
 * ya commiteado. Es el unico camino que produce un PROCESSING colgado con plata movida.
 *
 * <p>Que sea un tipo propio es para el log, no para el flujo: nadie la distingue por tipo.
 * Si alguien la atrapara en algun lado, el escenario dejaria de reproducir lo que dice
 * reproducir.
 */
public class SimulatedProviderCrashException extends RuntimeException {

    public SimulatedProviderCrashException(String message) {
        super(message);
    }
}
