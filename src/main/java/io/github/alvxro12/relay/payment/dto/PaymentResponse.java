package io.github.alvxro12.relay.payment.dto;

import io.github.alvxro12.relay.payment.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        PaymentStatus status,
        Long amount,
        String currency,
        String externalReference,
        Instant createdAt
) {
    public static PaymentResponse from(io.github.alvxro12.relay.payment.Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getExternalReference(),
                payment.getCreatedAt()
        );
    }
}