package io.github.alvxro12.relay.messaging;

import io.github.alvxro12.relay.payment.ChargeRequestedEvent;
import io.github.alvxro12.relay.payment.PaymentEventPublisher;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RabbitPaymentEventPublisher implements PaymentEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public RabbitPaymentEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publishChargeRequested(ChargeRequestedEvent event) {
        rabbitTemplate.convertAndSend(
                RabbitConfig.PAYMENTS_EXCHANGE,
                RabbitConfig.CHARGE_ROUTING_KEY,
                event
        );
    }
}