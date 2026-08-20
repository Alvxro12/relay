package io.github.alvxro12.relay.webhook;

/**
 * Puerto de salida hacia la mensajería. Igual que PaymentEventPublisher: el
 * paquete de dominio no conoce el broker, la implementación vive en messaging/.
 */
public interface WebhookEventPublisher {

    void publishWebhookReceived(WebhookReceivedEvent event);
}
