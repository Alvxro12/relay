package io.github.alvxro12.relay.webhook;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "webhook_events",
        // Idempotencia de eventos duplicados: el proveedor reintenta agresivamente
        // ante timeouts, así que la misma entrega puede llegar varias veces.
        // La unique constraint es la que decide, no el chequeo previo en memoria.
        uniqueConstraints = @UniqueConstraint(
                name = "uk_webhook_provider_event_id",
                columnNames = "provider_event_id"
        )
)
@Getter
@Setter
@NoArgsConstructor
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "provider_event_id", nullable = false)
    private String providerEventId;

    // Se guarda el cuerpo tal cual llegó: es lo que se firmó con HMAC y lo único
    // que permite reconstruir qué mandó el proveedor si hay que auditar.
    // El NOT NULL va dentro del columnDefinition: cuando se declara explícito,
    // Hibernate lo usa tal cual y descarta el nullable = false del @Column.
    @Column(name = "raw_payload", nullable = false, columnDefinition = "NVARCHAR(MAX) NOT NULL")
    private String rawPayload;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    // Momento en que el consumer terminó de resolver el evento, sea PROCESSED o FAILED.
    @Column(name = "processed_at")
    private Instant processedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WebhookEventStatus status;

    public static WebhookEvent received(String providerEventId, String rawPayload) {
        WebhookEvent event = new WebhookEvent();
        event.providerEventId = providerEventId;
        event.rawPayload = rawPayload;
        event.receivedAt = Instant.now();
        event.status = WebhookEventStatus.RECEIVED;
        return event;
    }

    public void markProcessed() {
        this.status = WebhookEventStatus.PROCESSED;
        this.processedAt = Instant.now();
    }

    public void markFailed() {
        this.status = WebhookEventStatus.FAILED;
        this.processedAt = Instant.now();
    }
}
