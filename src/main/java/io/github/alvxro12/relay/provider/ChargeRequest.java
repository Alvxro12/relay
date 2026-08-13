package io.github.alvxro12.relay.provider;

public record ChargeRequest(
        Long amount,
        String currency
) {
}
