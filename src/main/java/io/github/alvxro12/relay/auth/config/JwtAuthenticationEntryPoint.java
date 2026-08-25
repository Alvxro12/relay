package io.github.alvxro12.relay.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Único lugar que escribe el 401 de la API.
 *
 * Existe porque los rechazos llegan por dos caminos distintos y tienen que dar la
 * misma respuesta: un token presente pero inválido lo corta el filtro, que llama acá
 * directamente; un request sin header Authorization no lo corta nadie hasta el
 * AuthorizationFilter, que lanza AccessDeniedException y termina acá vía
 * ExceptionTranslationFilter. Si cada camino escribiera su propia respuesta,
 * "sin token" y "token inválido" serían distinguibles desde afuera sin necesidad.
 *
 * <p>El cuerpo no pasa por GlobalExceptionHandler: la cadena de filtros corre antes
 * del DispatcherServlet, así que ningún @ExceptionHandler ve estas fallas. Y es un
 * literal en vez de un DTO serializado porque es una constante de un solo campo:
 * meter un ObjectMapper acá ataría el 401 a la versión de Jackson del classpath
 * (Boot 4 trae la 3, jjwt arrastra la 2) sin ganar nada.
 */
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** Sin descripción del motivo: por qué falló la validación —firma, exp, aud,
     *  algoritmo, merchant deshabilitado— es información para quien prueba tokens. */
    private static final byte[] BODY =
            "{\"error\":\"invalid_token\"}".getBytes(StandardCharsets.UTF_8);

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(BODY.length);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.getOutputStream().write(BODY);
    }
}
