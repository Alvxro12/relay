package io.github.alvxro12.relay.auth;

/**
 * El token presentado no sirve. Igual que con InvalidClientException, un solo tipo
 * para todos los motivos —firma inválida, expirado, iss/aud equivocados, algoritmo
 * no permitido, merchant inexistente o deshabilitado—: quien prueba tokens no debe
 * poder deducir cuál de las validaciones falló.
 *
 * El motivo real sí se loguea (en DEBUG y sin el token), que es donde hace falta.
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String reason) {
        super(reason);
    }

    public InvalidTokenException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
