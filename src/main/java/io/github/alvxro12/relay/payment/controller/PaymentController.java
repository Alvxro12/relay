package io.github.alvxro12.relay.payment.controller;

import io.github.alvxro12.relay.payment.Payment;
import io.github.alvxro12.relay.payment.PaymentResult;
import io.github.alvxro12.relay.payment.PaymentStatus;
import io.github.alvxro12.relay.payment.dto.CreatePaymentRequest;
import io.github.alvxro12.relay.payment.dto.PaymentResponse;
import io.github.alvxro12.relay.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @RequestHeader("X-Merchant-Id") UUID merchantId,          // TODO: reemplazar por Spring Security cuando exista auth real
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request
    ) {
        PaymentResult result = paymentService.createPayment(merchantId, idempotencyKey, request);
        PaymentResponse body = PaymentResponse.from(result.payment());

        return switch (result.outcome()) {
            case CREATED -> ResponseEntity
                    .created(URI.create("/payments/" + result.payment().getId()))
                    .body(body);

            case REPLAY -> ResponseEntity.ok(body);

            case CONFLICT -> ResponseEntity.status(409).body(body);
        };
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> findById(
            @RequestHeader("X-Merchant-Id") UUID merchantId,
            @PathVariable UUID id
    ) {
        return paymentService.findById(merchantId, id)
                .map(PaymentResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/needs-review")
    public ResponseEntity<List<PaymentResponse>> needsReview(
            @RequestParam(defaultValue = "15") long olderThanMinutes
    ) {
        List<Payment> payments = paymentService.findPaymentsNeedingReview(olderThanMinutes);
        List<PaymentResponse> response = payments.stream().map(PaymentResponse::from).toList();
        return ResponseEntity.ok(response);
    }
}