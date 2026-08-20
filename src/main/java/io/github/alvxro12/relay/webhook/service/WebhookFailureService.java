package io.github.alvxro12.relay.webhook.service;

import io.github.alvxro12.relay.webhook.WebhookEvent;
import io.github.alvxro12.relay.webhook.WebhookEventRepository;
import io.github.alvxro12.relay.webhook.WebhookEventStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Marca un evento como FAILED desde afuera de la transacción del consumer, que
 * para ese momento ya hizo rollback. Lo usa el recoverer cuando se agotaron los
 * reintentos y el mensaje se va a la DLQ.
 */
@Service
public class WebhookFailureService {

    private static final Logger log = LoggerFactory.getLogger(WebhookFailureService.class);

    private final WebhookEventRepository webhookEventRepository;

    public WebhookFailureService(WebhookEventRepository webhookEventRepository) {
        this.webhookEventRepository = webhookEventRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID webhookEventId, String reason) {
        WebhookEvent webhookEvent = webhookEventRepository.findById(webhookEventId).orElse(null);
        if (webhookEvent == null) {
            log.error("Se agotaron los reintentos de un webhook que ya no está en la DB: id={}", webhookEventId);
            return;
        }

        // Si otro camino ya lo resolvió, no lo pisamos.
        if (webhookEvent.getStatus() != WebhookEventStatus.RECEIVED) {
            return;
        }

        webhookEvent.markFailed();
        webhookEventRepository.saveAndFlush(webhookEvent);

        log.error("Webhook {} marcado FAILED tras agotar los reintentos y enviarse a la DLQ. Motivo: {}",
                webhookEvent.getProviderEventId(), reason);
    }
}
