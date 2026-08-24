package io.github.alvxro12.relay.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Un merchant es el cliente de la API. Su {@code id} es el mismo UUID que hasta
 * ahora viajaba en el header {@code X-Merchant-Id}: la diferencia es que ahora
 * existe como fila y solo se obtiene presentando credenciales válidas.
 */
@Entity
@Table(
        name = "merchants",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_merchants_client_id",
                columnNames = "client_id"
        )
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** Identificador público del cliente. No es secreto: se loguea sin enmascarar. */
    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    /**
     * Hash BCrypt del clientSecret. El secret en claro no se persiste ni se puede
     * recuperar: se muestra una sola vez cuando se crea el merchant.
     */
    @Column(name = "client_secret_hash", nullable = false, length = 100)
    private String clientSecretHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MerchantStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isActive() {
        return status == MerchantStatus.ACTIVE;
    }
}
