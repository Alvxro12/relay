package io.github.alvxro12.relay.webhook.service;

import io.github.alvxro12.relay.webhook.WebhookEvent;
import io.github.alvxro12.relay.webhook.WebhookEventPublisher;
import io.github.alvxro12.relay.webhook.WebhookEventRepository;
import io.github.alvxro12.relay.webhook.WebhookReceivedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class WebhookEventInsertService {

    private final WebhookEventRepository webhookEventRepository;
    private final WebhookEventPublisher eventPublisher;

    public WebhookEventInsertService(WebhookEventRepository webhookEventRepository,
                                     WebhookEventPublisher eventPublisher) {
        this.webhookEventRepository = webhookEventRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Transacción propia para que la violación de la unique constraint llegue al
     * llamador como excepción sin arrastrar ninguna transacción externa a rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WebhookEvent insert(String providerEventId, String rawPayload) {
        WebhookEvent saved = webhookEventRepository.saveAndFlush(
                WebhookEvent.received(providerEventId, rawPayload)
        );

        // Mismo criterio que el flujo de charge: publicar recién después del commit.
        // Si se publica antes, el consumer puede leer la fila antes de que sea
        // visible fuera de esta transacción y fallar por "no encontrado".
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishWebhookReceived(
                        new WebhookReceivedEvent(saved.getId(), saved.getProviderEventId())
                );
            }
        });

        return saved;
    }
}
