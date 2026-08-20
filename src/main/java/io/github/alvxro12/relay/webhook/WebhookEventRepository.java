package io.github.alvxro12.relay.webhook;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    boolean existsByProviderEventId(String providerEventId);

    Optional<WebhookEvent> findByProviderEventId(String providerEventId);
}
