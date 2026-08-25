package io.github.alvxro12.relay.auth.controller;

import io.github.alvxro12.relay.auth.InvalidClientException;
import io.github.alvxro12.relay.auth.RateLimitExceededException;
import io.github.alvxro12.relay.auth.dto.AuthErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduce los fallos que ocurren dentro de TokenController.
 *
 * <p>Acotado a ese controller con {@code assignableTypes} y no global: lo que hace es
 * convertir cualquier problema del endpoint de token en una respuesta sin detalle, y
 * eso es exactamente lo que <em>no</em> queremos en el resto de la API, donde un
 * mensaje de validación útil no le cuesta nada a nadie.
 *
 * <p>HIGHEST_PRECEDENCE no es decorativo: GlobalExceptionHandler tiene un
 * {@code @ExceptionHandler(Exception.class)} que matchea todo, y el resolver de Spring
 * recorre los advice en orden quedándose con el primero que tenga un método aplicable.
 * Sin este @Order, estas excepciones podrían caer en esa red de seguridad, devolver
 * 500 y —peor— loguear el stacktrace de un request que lleva un secret adentro.
 */
@RestControllerAdvice(assignableTypes = TokenController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    @ExceptionHandler(InvalidClientException.class)
    public ResponseEntity<AuthErrorResponse> handleInvalidClient(InvalidClientException ex) {
        // Sin log: el intento fallido ya lo cuenta y lo loguea el rate limiter, que es
        // el que tiene el contexto. Loguear acá tambien duplicaria la linea.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(AuthErrorResponse.invalidClient());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<AuthErrorResponse> handleRateLimit(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(AuthErrorResponse.tooManyRequests());
    }

    /**
     * Cuerpo que Jackson no puede leer. Se atiende acá y no en GlobalExceptionHandler
     * porque el cuerpo de <em>este</em> request contiene un clientSecret: el handler
     * genérico loguearía la excepción, y el mensaje de un error de parseo puede
     * arrastrar un fragmento del JSON de origen. Acá no se loguea nada y la respuesta
     * es la misma que para credenciales incorrectas.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<AuthErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(AuthErrorResponse.invalidClient());
    }
}
