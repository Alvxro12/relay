package io.github.alvxro12.relay.messaging;

import io.github.alvxro12.relay.webhook.WebhookReceivedEvent;
import io.github.alvxro12.relay.webhook.service.WebhookFailureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.stereotype.Component;

/**
 * Se ejecuta cuando el consumer de webhooks agotó los reintentos. Deja el evento
 * marcado como FAILED en la DB antes de rechazar el mensaje, para que el estado
 * en la DLQ y el estado en la tabla cuenten la misma historia.
 */
@Component
public class WebhookProcessingRecoverer implements MessageRecoverer {

    private static final Logger log = LoggerFactory.getLogger(WebhookProcessingRecoverer.class);

    private final MessageConverter messageConverter;
    private final WebhookFailureService failureService;
    private final RejectAndDontRequeueRecoverer delegate = new RejectAndDontRequeueRecoverer();

    public WebhookProcessingRecoverer(MessageConverter messageConverter,
                                      WebhookFailureService failureService) {
        this.messageConverter = messageConverter;
        this.failureService = failureService;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        try {
            Object payload = messageConverter.fromMessage(message);
            if (payload instanceof WebhookReceivedEvent event) {
                failureService.markFailed(event.webhookEventId(), describe(cause));
            } else {
                log.error("Mensaje inesperado en {}, no se puede marcar FAILED: {}",
                        RabbitConfig.WEBHOOK_QUEUE, payload);
            }
        } catch (Exception e) {
            // No dejamos que un fallo marcando FAILED impida el envío a la DLQ:
            // perder el mensaje sería peor que perder la marca en la DB.
            log.error("No se pudo marcar como FAILED el webhook que va a la DLQ", e);
        }

        // Rechaza sin reencolar: junto con x-dead-letter-exchange en la cola, el
        // mensaje va a la DLQ en vez de volver a la cola para siempre.
        delegate.recover(message, cause);
    }

    private String describe(Throwable cause) {
        Throwable root = cause;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
