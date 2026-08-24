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

    /**
     * El toString que genera un record incluye todos los campos. Sin este override,
     * cualquier cosa que loguee el objeto —un mensaje de error de binding, un
     * {@code log.debug("request={}", request)} escrito sin pensar— escribe el
     * clientSecret en claro en el log. Es una linea que tapa un agujero que aparece
     * solo, y por eso esta.
     */
    @Override
    public String toString() {
        return "TokenRequest[clientId=" + clientId + ", clientSecret=***]";
    }
}
