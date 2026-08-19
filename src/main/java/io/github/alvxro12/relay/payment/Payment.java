package io.github.alvxro12.relay.payment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "payments",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_merchant_idempotency_key",
                columnNames = {"merchant_id", "idempotency_key"}
        ),
        // Índice NO único a propósito: en SQL Server un UNIQUE trata los NULL
        // como iguales y admite uno solo, y la mayoría de los pagos tienen
        // provider_transaction_id en NULL (ver el campo más abajo).
        indexes = @Index(
                name = "ix_payments_provider_transaction_id",
                columnList = "provider_transaction_id"
        )
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false)
    private Long amount;          // centavos, nunca decimal

    @Column(nullable = false, length = 3)
    private String currency;      // ISO 4217

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    private String reference;     // opcional, referencia del merchant

    // Id de la transacción en el proveedor: es la clave con la que los webhooks
    // correlacionan contra este pago. Nullable a propósito: un TIMEOUT nunca lo
    // recibe (el provider lanza excepción, no hay ChargeResult) y un SERVER_ERROR
    // lo devuelve en null por diseño.
    @Column(name = "provider_transaction_id")
    private String providerTransactionId;

    // Optimistic locking: el consumer de charge y el de webhook pueden escribir
    // esta misma fila. El que pierde recibe OptimisticLockingFailureException,
    // el interceptor de reintentos lo reprocesa y al releer la fila vuelve a
    // pasar por las guardas de estado en vez de pisar el resultado del otro.
    @Version
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}