package io.github.alvxro12.relay.auth.dto;

/**
 * Credenciales del flujo client_credentials.
 *
 * Sin anotaciones de validación a propósito: un campo faltante o en blanco tiene
 * que salir por el mismo camino que un secret incorrecto (401 invalid_client) y no
 * por el 422 de @Valid, que sería una respuesta distinta para un caso que igual es
 * un intento de autenticación fallido.
 */
public record TokenRequest(
        String clientId,
        String clientSecret
) {
}
