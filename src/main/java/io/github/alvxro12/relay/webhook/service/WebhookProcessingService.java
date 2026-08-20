package io.github.alvxro12.relay.webhook.service;

import io.github.alvxro12.relay.payment.Payment;
import io.github.alvxro12.relay.payment.PaymentRepository;
import io.github.alvxro12.relay.payment.PaymentStatus;
import io.github.alvxro12.relay.webhook.PaymentNotCorrelatedException;
import io.github.alvxro12.relay.webhook.WebhookEvent;
import io.github.alvxro12.relay.webhook.WebhookEventRepository;
import io.github.alvxro12.relay.webhook.WebhookEventStatus;
import io.github.alvxro12.relay.webhook.WebhookReceivedEvent;
import io.github.alvxro12.relay.webhook.dto.ProviderWebhookPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Service
public class WebhookProcessingService {

    private static final Logger log = LoggerFactory.getLogger(WebhookProcessingService.class);

    private static final Map<String, PaymentStatus> TYPE_TO_STATUS = Map.of(
            "payment.succeeded", PaymentStatus.SUCCEEDED,
            "payment.failed", PaymentStatus.FAILED
    );

    private final WebhookEventRepository webhookEventRepository;
    private final PaymentRepository paymentRepository;
    private final ObjectMapper objectMapper;

    public WebhookProcessingService(WebhookEventRepository webhookEventRepository,
                                    PaymentRepository paymentRepository,
                                    ObjectMapper objectMapper) {
        this.webhookEventRepository = webhookEventRepository;
        this.paymentRepository = paymentRepository;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "webhook.processing.queue", containerFactory = "webhookListenerContainerFactory")
    @Transactional
    public void handleWebhookReceived(WebhookReceivedEvent event) {
        WebhookEvent webhookEvent = webhookEventRepository.findById(event.webhookEventId())
                .orElseThrow(() -> new IllegalStateException(
                        "WebhookEvent not found for received event: " + event.webhookEventId()));

        // Idempotencia del consumer: un evento ya resuelto no se vuelve a procesar,
        // pase lo que pase con la entrega del mensaje.
        if (webhookEvent.getStatus() != WebhookEventStatus.RECEIVED) {
            return; // ack igual, sin recobrar
        }

        ProviderWebhookPayload payload;
        try {
            payload = objectMapper.readValue(webhookEvent.getRawPayload(), ProviderWebhookPayload.class);
        } catch (Exception e) {
            // Reintentar no cambia un payload que ya está guardado y es ilegible.
            failWithoutRetry(webhookEvent, "el payload guardado no se pudo deserializar: " + e.getMessage());
            return;
        }

        PaymentStatus targetStatus = TYPE_TO_STATUS.get(payload.type());
        if (targetStatus == null) {
            failWithoutRetry(webhookEvent, "tipo de evento no soportado: " + payload.type());
            return;
        }

        String providerTransactionId = payload.providerTransactionId();
        if (providerTransactionId == null || providerTransactionId.isBlank()) {
            failWithoutRetry(webhookEvent, "el evento no trae providerTransactionId: no hay con qué correlacionar");
            return;
        }

        // Si no está, puede ser transitorio (el webhook ganó de mano al consumer
        // de charge), así que se deja fallar para que el interceptor reintente
        // con backoff. Agotados los intentos: DLQ + FAILED, nunca reintento infinito.
        Payment payment = paymentRepository.findByProviderTransactionId(providerTransactionId)
                .orElseThrow(() -> new PaymentNotCorrelatedException(providerTransactionId));

        if (isTerminal(payment.getStatus())) {
            // Los webhooks no llegan en orden garantizado: dejar que un evento tardío
            // revierta un estado terminal reactivaría un pago ya resuelto.
            log.warn("Webhook {} no aplicado: el pago {} ya está en {} y el evento intentaba dejarlo en {}. "
                            + "No se revierte un estado terminal.",
                    webhookEvent.getProviderEventId(), payment.getId(), payment.getStatus(), targetStatus);
            webhookEvent.markProcessed();
            webhookEventRepository.saveAndFlush(webhookEvent);
            return;
        }

        payment.setStatus(targetStatus);
        paymentRepository.saveAndFlush(payment);

        webhookEvent.markProcessed();
        webhookEventRepository.saveAndFlush(webhookEvent);
        // ack automático al retornar sin excepción
    }

    /**
     * Falla que ningún reintento puede arreglar: se marca FAILED y se hace ack,
     * así el mensaje no rebota por la cola hasta la DLQ sin motivo.
     */
    private void failWithoutRetry(WebhookEvent webhookEvent, String reason) {
        log.error("Webhook {} marcado FAILED sin reintentos. Motivo: {}",
                webhookEvent.getProviderEventId(), reason);
        webhookEvent.markFailed();
        webhookEventRepository.saveAndFlush(webhookEvent);
    }

    /**
     * Terminal es solo SUCCEEDED o FAILED. AWAITING_CONFIRMATION queda deliberadamente
     * afuera: es el estado desde el que un webhook tiene que poder resolver el pago, y
     * de hecho es la razón por la que este consumer existe.
     */
    private boolean isTerminal(PaymentStatus status) {
        return status == PaymentStatus.SUCCEEDED || status == PaymentStatus.FAILED;
    }
}
