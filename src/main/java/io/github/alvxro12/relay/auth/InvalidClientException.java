package io.github.alvxro12.relay.auth;

/**
 * Credenciales de cliente rechazadas. Una sola excepción para los tres casos
 * —clientId inexistente, secret incorrecto, merchant deshabilitado— porque la
 * respuesta tiene que ser indistinguible entre ellos: quien prueba clientIds no
 * debe poder enumerar cuáles existen.
 *
 * Sin causa ni mensaje variable: lo que se loguea del intento fallido se arma en
 * el punto de la falla, no viaja en la excepción hasta el handler.
 */
public class InvalidClientException extends RuntimeException {

    public InvalidClientException() {
        super("invalid_client");
    }
}
