package io.github.alvxro12.relay.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuracion del proveedor de pagos.
 *
 * <p>Separada de {@code relay.reconciliation} a proposito, aunque la use la
 * reconciliacion: son preguntas distintas. {@code stale-after} responde "cuando me
 * preocupo", que es politica nuestra; {@code not-found-grace} responde "cuando le creo al
 * proveedor", que es un hecho sobre ellos. Juntas invitarian a tunearlas como si fueran lo
 * mismo, y se ajustan mirando cosas distintas.
 *
 * @param notFoundGrace cuanto tiene que haber pasado desde el intento de cobro para que un
 *                      NOT_FOUND del proveedor se pueda tomar como definitivo. Ver
 *                      {@code NotFoundGracePolicy}.
 */
@ConfigurationProperties(prefix = "relay.provider")
public record ProviderProperties(@DefaultValue("30m") Duration notFoundGrace) {
}
