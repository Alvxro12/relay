package io.github.alvxro12.relay.auth.dto;

/**
 * Respuesta del endpoint de token.
 *
 * Nombres en camelCase y no el snake_case de RFC 6749 (`access_token`) por
 * consistencia con el resto de la API y con el request, que ya usa `clientId`.
 * Esto no es un servidor OAuth2 interoperable: es el flujo client_credentials
 * aplicado a un solo cliente conocido.
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn      // segundos
) {
}
