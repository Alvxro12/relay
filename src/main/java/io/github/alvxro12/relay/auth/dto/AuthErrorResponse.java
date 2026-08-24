package io.github.alvxro12.relay.auth.dto;

/**
 * Cuerpo de error de autenticación. Un solo campo con un código fijo: cualquier
 * descripción que explique <em>por qué</em> falló le dice a quien prueba
 * credenciales si el clientId existe.
 */
public record AuthErrorResponse(String error) {

    public static AuthErrorResponse invalidClient() {
        return new AuthErrorResponse("invalid_client");
    }

    /** El unico error de este endpoint que si dice algo: cuantos intentos van no
     *  depende de si el clientId existe, asi que no filtra nada. */
    public static AuthErrorResponse tooManyRequests() {
        return new AuthErrorResponse("too_many_requests");
    }
}
