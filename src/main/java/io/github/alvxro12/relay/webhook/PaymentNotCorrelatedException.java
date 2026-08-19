package io.github.alvxro12.relay.webhook;

/**
 * No hay ningún Payment con el providerTransactionId que trae el evento.
 * <p>
 * Puede ser transitorio: el proveedor a veces manda el webhook antes de que
 * nuestro consumer de charge haya commiteado el providerTransactionId. Por eso
 * se deja propagar, para que el interceptor reintente con backoff. Agotados los
 * intentos, el recoverer manda el mensaje a la DLQ y marca el evento como FAILED.
 */
public class PaymentNotCorrelatedException extends RuntimeException {

    public PaymentNotCorrelatedException(String providerTransactionId) {
        super("No hay Payment para providerTransactionId=" + providerTransactionId);
    }
}
