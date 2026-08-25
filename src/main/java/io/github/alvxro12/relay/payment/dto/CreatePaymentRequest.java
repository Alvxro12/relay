package io.github.alvxro12.relay.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

public record CreatePaymentRequest(

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than zero")
        Long amount,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO 4217 code")
        String currency,

        String externalReference   // opcional, sin validación: es la etiqueta del merchant
) {
}