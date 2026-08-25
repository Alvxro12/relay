package io.github.alvxro12.relay.payment.controller;

import io.github.alvxro12.relay.auth.AuthenticatedMerchant;
import io.github.alvxro12.relay.payment.Payment;
import io.github.alvxro12.relay.payment.PaymentResult;
import io.github.alvxro12.relay.payment.dto.CreatePaymentRequest;
import io.github.alvxro12.relay.payment.dto.PaymentResponse;
import io.github.alvxro12.relay.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * El merchantId no es un parámetro de ninguno de estos endpoints: llega en el
 * principal que dejó JwtAuthenticationFilter, y ese principal se arma a partir de un
 * token que firmó Relay. Antes venía en el header X-Merchant-Id, o sea que era una
 * afirmación del cliente: cualquiera que conociera el UUID de otro comercio podía
 * crear pagos a su nombre y leer los suyos.
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @AuthenticationPrincipal AuthenticatedMerchant merchant,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request
    ) {
        // La Idempotency-Key sigue siendo del cliente, pero ya no es global: la unicidad
        // es (merchant_id, idempotency_key), así que dos merchants pueden mandar la misma
        // key y crear dos pagos independientes sin pisarse.
        PaymentResult result = paymentService.createPayment(merchant.merchantId(), idempotencyKey, request);
        PaymentResponse body = PaymentResponse.from(result.payment());

        return switch (result.outcome()) {
            case CREATED -> ResponseEntity
                    .created(URI.create("/payments/" + result.payment().getId()))
                    .body(body);

            case REPLAY -> ResponseEntity.ok(body);

            case CONFLICT -> ResponseEntity.status(409).body(body);
        };
    }

    /**
     * 404 y no 403 cuando el pago es de otro merchant. Un 403 confirmaría que ese UUID
     * existe: con eso se enumera qué pagos hay en el sistema aunque no se pueda leer
     * ninguno. Para este merchant, el pago de otro y un pago inexistente son lo mismo,
     * y la respuesta lo refleja.
     */
    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> findById(
            @AuthenticationPrincipal AuthenticatedMerchant merchant,
            @PathVariable UUID id
    ) {
        return paymentService.findById(merchant.merchantId(), id)
                .map(PaymentResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/needs-review")
    public ResponseEntity<List<PaymentResponse>> needsReview(
            @AuthenticationPrincipal AuthenticatedMerchant merchant,
            @RequestParam(required = false) Long olderThanMinutes
    ) {
        // Sin el parámetro manda el umbral configurado de cada estado, que es lo que
        // corresponde: PROCESSING y AWAITING_CONFIRMATION no se miden con la misma vara.
        // Con el parámetro, un operador pisa los tres a mano para una investigación puntual.
        List<Payment> payments = olderThanMinutes == null
                ? paymentService.findPaymentsNeedingReview(merchant.merchantId())
                : paymentService.findPaymentsNeedingReview(merchant.merchantId(), Duration.ofMinutes(olderThanMinutes));
        List<PaymentResponse> response = payments.stream().map(PaymentResponse::from).toList();
        return ResponseEntity.ok(response);
    }
}
