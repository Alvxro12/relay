package io.github.alvxro12.relay.provider;

public interface PaymentProvider {

    ChargeResult charge(ChargeRequest request);
}
