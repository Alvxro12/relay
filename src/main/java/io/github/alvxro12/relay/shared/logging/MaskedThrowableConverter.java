package io.github.alvxro12.relay.shared.logging;

import ch.qos.logback.classic.spi.IThrowableProxy;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/**
 * Reemplaza a {@code %wEx}: el stacktrace con las credenciales borradas.
 *
 * <p>Hace falta aparte del mensaje porque es justo por acá por donde se escapan estas
 * cosas. Un error de parseo del cuerpo del request de token trae el JSON de origen en
 * el mensaje de la excepción, y ese JSON tiene el clientSecret adentro.
 *
 * <p>Extiende el converter de Spring Boot y no el de logback para conservar el formato
 * que Boot ya le da al stacktrace.
 */
public class MaskedThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return CredentialMasker.mask(super.throwableProxyToString(tp));
    }
}
