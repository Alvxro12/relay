package io.github.alvxro12.relay.auth.controller;

import io.github.alvxro12.relay.auth.InvalidClientException;
import io.github.alvxro12.relay.auth.dto.AuthErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduce los fallos de autenticación que ocurren <em>dentro</em> del controller.
 *
 * HIGHEST_PRECEDENCE no es decorativo: GlobalExceptionHandler tiene un
 * {@code @ExceptionHandler(Exception.class)} que matchea todo, y el resolver de
 * Spring recorre los advice en orden y se queda con el primero que tenga un método
 * aplicable. Sin este @Order, InvalidClientException podría caer en la red de
 * seguridad de GlobalExceptionHandler y devolver 500 en vez de 401.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    @ExceptionHandler(InvalidClientException.class)
    public ResponseEntity<AuthErrorResponse> handleInvalidClient(InvalidClientException ex) {
        // Nada de log del intento acá: lo loguea el rate limiter, que es el que tiene
        // el contexto (clientId, IP, cuántos intentos van).
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("WWW-Authenticate", "Bearer")
                .body(AuthErrorResponse.invalidClient());
    }
}
