package io.github.alvxro12.relay.auth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import io.github.alvxro12.relay.auth.dto.TokenRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las credenciales no llegan al log.
 *
 * <p>Se prueba el encoder del appender y no lo que sale por consola porque el
 * enmascarado vive en el patrón: capturar el logger con un ListAppender vería el
 * mensaje <em>antes</em> de formatear y daría verde con el patrón roto.
 */
@SpringBootTest
class CredentialMaskingTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    /**
     * El toString de un record incluye todos los campos. Sin el override, cualquier
     * cosa que loguee el objeto —un mensaje de binding, un log.debug distraído—
     * escribe el clientSecret en claro.
     */
    @Test
    void tokenRequestToString_doesNotExposeTheSecret() {
        TokenRequest request = new TokenRequest("mch_abc", "un-secret-que-no-debe-aparecer");

        assertThat(request.toString())
                .doesNotContain("un-secret-que-no-debe-aparecer")
                .contains("mch_abc")
                .contains("***");
    }

    @Test
    void logPattern_masksBearerTokens() {
        String rendered = render("Llamando con Authorization: Bearer " + TOKEN + " al proveedor");

        assertThat(rendered).doesNotContain(TOKEN);
        assertThat(rendered).contains("Bearer ***");
    }

    @Test
    void logPattern_masksClientSecrets() {
        String json = "{\"clientId\":\"mch_abc\",\"clientSecret\":\"NoDebeAparecerNunca\"}";

        String rendered = render("Cuerpo recibido: " + json);

        assertThat(rendered).doesNotContain("NoDebeAparecerNunca");
        assertThat(rendered).contains("mch_abc");   // el clientId no es secreto
    }

    @Test
    void logPattern_masksSecretsInsideStackTraces() {
        Throwable cause = new IllegalStateException("fallo con Authorization: Bearer " + TOKEN);

        String rendered = render("Error inesperado", cause);

        assertThat(rendered).doesNotContain(TOKEN);
    }

    private String render(String message) {
        return render(message, null);
    }

    /** Pasa un evento sintético por el mismo encoder que usa el appender de consola. */
    private String render(String message, Throwable throwable) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger root = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);

        var appender = root.getAppender("CONSOLE");
        assertThat(appender)
                .as("logback-spring.xml debe definir el appender CONSOLE")
                .isInstanceOf(OutputStreamAppender.class);

        @SuppressWarnings("unchecked")
        Encoder<ILoggingEvent> encoder = ((OutputStreamAppender<ILoggingEvent>) appender).getEncoder();

        LoggingEvent event = new LoggingEvent(
                CredentialMaskingTest.class.getName(), root, Level.INFO, message, throwable, null);

        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }
}
