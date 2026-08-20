package io.github.alvxro12.relay.messaging;

import io.github.alvxro12.relay.webhook.WebhookEventPublisher;
import io.github.alvxro12.relay.webhook.WebhookReceivedEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RabbitWebhookEventPublisher implements WebhookEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public RabbitWebhookEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publishWebhookReceived(WebhookReceivedEvent event) {
        rabbitTemplate.convertAndSend(
                RabbitConfig.PAYMENTS_EXCHANGE,
                RabbitConfig.WEBHOOK_ROUTING_KEY,
                event
        );
    }
}
