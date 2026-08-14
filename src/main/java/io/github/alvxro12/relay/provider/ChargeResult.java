package io.github.alvxro12.relay.provider;

public record ChargeResult(
        ChargeStatus status,
        String providerTransactionId
) {
}
