package io.github.alvxro12.relay.webhook;

import java.util.UUID;

/**
 * Mensaje que viaja por la cola. Lleva solo el id de la fila, no el payload:
 * el cuerpo crudo ya está persistido y esa es la única fuente de verdad, así
 * evitamos que el mensaje y la DB puedan divergir.
 */
public record WebhookReceivedEvent(
        UUID webhookEventId,
        String providerEventId
) {
}
