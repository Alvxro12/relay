package io.github.alvxro12.relay.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Cuándo deja la reconciliación de preguntarle al proveedor por un pago.
 *
 * <p>Es lo que separa "esperando" de "en limbo". Un pago cuya consulta devuelve UNKNOWN una
 * y otra vez no se resuelve nunca solo: sin un reloj que lo corte, la reconciliación lo
 * consulta para siempre y nadie lo mira jamás. Pasado el umbral se deja de preguntar, y el
 * pago se queda donde ya era visible —{@code GET /v1/payments/needs-review} y el WARN de
 * cada ciclo—, que es lo que acá significa revisión manual.
 *
 * <p><b>Rendirse no escribe nada.</b> Se calcula contra {@code charge_attempted_at}, que se
 * escribe una sola vez y no se mueve nunca. Un contador de intentos, o una marca de "ya me
 * rendí", serían una escritura por consulta sobre la fila del pago: {@code updated_at} se
 * movería sola, el pago saldría de su propia ventana de staleness y la reconciliación
 * dejaría de verlo justo por haberlo mirado. Es el mismo problema que obligó a crear
 * {@code charge_attempted_at} en vez de medir desde {@code updated_at}.
 *
 * <p>Separada de {@link StalePaymentThresholds} aunque comparta el prefijo: {@code
 * stale-after} responde "cuándo me preocupo" y esto responde "cuándo dejo de intentar". Se
 * ajustan mirando cosas distintas.
 *
 * @param giveUpAfter cuánto tiempo desde el intento de cobro se sigue preguntando. El piso
 *                    lo fijan dos valores que ya existen. Tiene que ser mayor que {@code
 *                    relay.provider.not-found-grace} (30m): si no, el pago se rinde antes de
 *                    que un NOT_FOUND pueda autorizar el FAILED y ese camino no se ejecutaría
 *                    nunca. Y mayor que {@code stale-after.awaiting-confirmation} (6h), porque
 *                    escalar a revisión un pago que el otro umbral todavía considera normal
 *                    es tener dos configuraciones contradiciéndose.
 *                    <p>24h deja margen sobre las dos y está en la unidad correcta: rendirse
 *                    es entregarle el pago a una persona, y una persona lo mira al día
 *                    siguiente, no a las siete horas. De paso acota el costo — un UNKNOWN
 *                    perpetuo se consulta unas 288 veces y no infinitas.
 */
@ConfigurationProperties(prefix = "relay.reconciliation")
public record ReconciliationProperties(@DefaultValue("24h") Duration giveUpAfter) {
}
