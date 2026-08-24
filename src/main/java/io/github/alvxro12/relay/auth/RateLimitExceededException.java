package io.github.alvxro12.relay.auth;

/**
 * Se superó el límite de intentos del endpoint de token.
 *
 * A diferencia de InvalidClientException, esta sí lleva información hacia afuera
 * ({@code retryAfterSeconds}) y no filtra nada: cuánto falta para la próxima ventana
 * es igual para un clientId que existe y para uno que no.
 */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super("too_many_requests");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
