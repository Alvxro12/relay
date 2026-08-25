package io.github.alvxro12.relay.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled si el proveedor simulado hace caso a las referencias RELAY_TEST_*.
 *                Apagado por defecto: con esto en false, una referencia magica es una
 *                etiqueta cualquiera y el cobro sale normal. El default seguro es el que
 *                no permite que un merchant fuerce el resultado de su propio pago.
 */
@ConfigurationProperties(prefix = "relay.sandbox")
public record SandboxProperties(@DefaultValue("false") boolean enabled) {
}
