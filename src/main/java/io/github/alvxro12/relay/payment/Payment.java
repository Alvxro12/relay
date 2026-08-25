package io.github.alvxro12.relay.payment;

import jakarta.persistence.*;
import lombok.AccessLevel;
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

    /**
     * Etiqueta del merchant para este pago. Opcional, sin unique: dos pagos pueden
     * compartirla. El nombre del campo es el del contrato publico; la columna sigue
     * llamandose "reference" para no arrastrar una migracion de rename a este gate.
     */
    @Column(name = "reference")
    private String externalReference;

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
    //
    // No cubre la ventana del claim-then-call: entre el claim y el recordResult
    // la fila queda commiteada y sin lock durante todo el cobro, y recordResult
    // carga la entidad fresca, así que no hay versión vieja con la que chocar.
    // Esa carrera la cubre la guarda de estado terminal de recordResult.
    @Version
    private Long version;

    /**
     * Cuándo se llamó al proveedor. La ventana de gracia de NOT_FOUND se mide desde acá,
     * así que moverla después del claim alargaría esa ventana sin que nadie lo note: el
     * pago nunca la cumpliría y la reconciliación no lo resolvería nunca.
     *
     * <p>Se escribe una sola vez y la garantía la da {@link #markChargeAttempted}, no el
     * mapeo. {@code @Column(updatable = false)} sería lo natural para esto y <b>no
     * funciona acá</b>: el claim escribe sobre una fila que ya existe (PENDING a
     * PROCESSING es un UPDATE), y {@code updatable = false} hace que Hibernate excluya la
     * columna de ese UPDATE. El valor se descartaría en silencio y la columna quedaría
     * siempre en NULL. Verificado.
     *
     * <p>Sin setter público a propósito: la única forma de escribirla es el método de
     * abajo, que no pisa un valor existente.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "charge_attempted_at")
    private Instant chargeAttemptedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Marca el intento de cobro. Escribe una sola vez: un segundo claim sobre el mismo
     * pago —que hoy no puede pasar, porque el primero lo saca de PENDING— no movería la
     * marca, y con ella la ventana de gracia.
     */
    public void markChargeAttempted(Instant attemptedAt) {
        if (this.chargeAttemptedAt == null) {
            this.chargeAttemptedAt = attemptedAt;
        }
    }
}
