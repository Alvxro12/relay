package io.github.alvxro12.relay.payment.controller;

import io.github.alvxro12.relay.payment.Payment;
import io.github.alvxro12.relay.payment.PaymentResult;
import io.github.alvxro12.relay.payment.dto.CreatePaymentRequest;
import io.github.alvxro12.relay.payment.dto.PaymentResponse;
import io.github.alvxro12.relay.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Duration;
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
            @RequestHeader("X-Merchant-Id") UUID merchantId,
            @RequestParam(required = false) Long olderThanMinutes
    ) {
        // Sin el parámetro manda el umbral configurado de cada estado, que es lo que
        // corresponde: PROCESSING y AWAITING_CONFIRMATION no se miden con la misma vara.
        // Con el parámetro, un operador pisa los tres a mano para una investigación puntual.
        List<Payment> payments = olderThanMinutes == null
                ? paymentService.findPaymentsNeedingReview(merchantId)
                : paymentService.findPaymentsNeedingReview(merchantId, Duration.ofMinutes(olderThanMinutes));
        List<PaymentResponse> response = payments.stream().map(PaymentResponse::from).toList();
        return ResponseEntity.ok(response);
    }
}
