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

    List<Payment> findByStatusAndUpdatedAtBefore(PaymentStatus status, Instant threshold);
}
