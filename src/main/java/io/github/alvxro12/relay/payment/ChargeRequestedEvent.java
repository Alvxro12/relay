package io.github.alvxro12.relay.payment;

import java.util.UUID;

public record ChargeRequestedEvent(
        UUID paymentId,
        UUID merchantId,
        Long amount,
        String currency
) {
}
