package io.github.alvxro12.relay.payment;

public interface PaymentEventPublisher {
    void publishChargeRequested(ChargeRequestedEvent event);
}