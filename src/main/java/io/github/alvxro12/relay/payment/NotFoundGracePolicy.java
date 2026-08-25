package io.github.alvxro12.relay.payment;

import io.github.alvxro12.relay.provider.ProviderPaymentStatus;
import io.github.alvxro12.relay.provider.ProviderProperties;
import io.github.alvxro12.relay.provider.ProviderStatusResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Decide si un {@code NOT_FOUND} del proveedor alcanza para dar un pago por fallido.
 *
 * <p>{@code NOT_FOUND} es la <b>única</b> respuesta que autoriza a escribir {@code FAILED}:
 * las demás dejan el pago donde está o lo resuelven a favor. Por eso es la única que
 * necesita una guarda propia. Los otros errores de reconciliación dejan un pago esperando;
 * este le dice a un merchant que no cobró cuando sí cobró, y eso es plata perdida que nadie
 * revierte solo.
 *
 * <p>El riesgo concreto: un proveedor real puede contestar {@code NOT_FOUND} por un cobro
 * que todavía está creando de su lado. Sus lecturas no son inmediatamente consistentes con
 * sus escrituras, así que un {@code NOT_FOUND} recién intentado el cobro no prueba nada.
 *
 * <p>Esta clase es una función pura: no lee ni escribe la base, no llama al proveedor y no
 * está enganchada a la reconciliación todavía. Es solo la guarda.
 */
@Component
public class NotFoundGracePolicy {

    private final Duration grace;

    public NotFoundGracePolicy(ProviderProperties providerProperties) {
        this.grace = providerProperties.notFoundGrace();
    }

    /**
     * @return true solo si el proveedor dice que no conoce el cobro <b>y</b> pasó
     *         suficiente tiempo desde que se intentó como para creerle.
     */
    public boolean authorizesFailing(ProviderStatusResult inquiry, Payment payment, Instant now) {
        if (inquiry == null || inquiry.status() != ProviderPaymentStatus.NOT_FOUND) {
            // SUCCEEDED, FAILED, PENDING y UNKNOWN no pasan por acá: ninguno significa
            // "el cobro no ocurrió". UNKNOWN sobre todo —el proveedor tiene el cobro
            // registrado y no sabe cómo terminó— es el caso donde tocar el pago es
            // exactamente lo que no hay que hacer.
            return false;
        }

        Instant attemptedAt = payment.getChargeAttemptedAt();
        if (attemptedAt == null) {
            // Nunca se llegó a llamar al proveedor, así que no hay ventana que medir. Sin
            // esta guarda, un pago sin marca haría fallar el cálculo o —peor, si se tomara
            // el instante cero— quedaría autorizado siempre.
            return false;
        }

        return !now.isBefore(attemptedAt.plus(grace));
    }
}
