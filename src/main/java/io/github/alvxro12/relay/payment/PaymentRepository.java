package io.github.alvxro12.relay.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

    // Clave de correlación de los webhooks del proveedor.
    Optional<Payment> findByProviderTransactionId(String providerTransactionId);

    // Sin scope de merchant: la usa el job de reconciliación, que mira todo el sistema.
    List<Payment> findByStatusAndUpdatedAtBefore(PaymentStatus status, Instant threshold);

    // Con scope de merchant: la usa el endpoint de revisión, donde un comercio solo
    // puede ver lo suyo.
    List<Payment> findByMerchantIdAndStatusAndUpdatedAtBefore(
            UUID merchantId, PaymentStatus status, Instant threshold);
}
