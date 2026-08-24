package io.github.alvxro12.relay.shared.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Reemplaza a {@code %m} en el patrón de log: mismo mensaje, con las credenciales
 * borradas. Se registra como conversion rule en logback-spring.xml.
 */
public class MaskedMessageConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return CredentialMasker.mask(event.getFormattedMessage());
    }
}
