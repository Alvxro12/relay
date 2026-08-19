package io.github.alvxro12.relay.webhook.service;

import io.github.alvxro12.relay.webhook.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class WebhookIngestService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestService.class);

    private final WebhookEventRepository webhookEventRepository;
    private final WebhookEventInsertService insertService;
    private final ObjectMapper objectMapper;

    public WebhookIngestService(WebhookEventRepository webhookEventRepository,
                                WebhookEventInsertService insertService,
                                ObjectMapper objectMapper) {
        this.webhookEventRepository = webhookEventRepository;
        this.insertService = insertService;
        this.objectMapper = objectMapper;
    }

    /**
     * Persiste el evento y lo encola. Acá solo se saca el id del evento: la
     * validación de negocio (correlacionar el pago, mapear el estado) es cara y
     * corre después del 200, en el consumer.
     */
    public WebhookIngestOutcome ingest(String rawPayload) {
        String providerEventId = extractProviderEventId(rawPayload);
        if (providerEventId == null || providerEventId.isBlank()) {
            return WebhookIngestOutcome.UNPARSEABLE;
        }

        // Atajo barato para el caso común de reintento del proveedor.
        if (webhookEventRepository.existsByProviderEventId(providerEventId)) {
            log.debug("Webhook duplicado ignorado: {}", providerEventId);
            return WebhookIngestOutcome.DUPLICATE;
        }

        try {
            insertService.insert(providerEventId, rawPayload);
            return WebhookIngestOutcome.ACCEPTED;

        } catch (DataIntegrityViolationException e) {
            // Dos entregas del mismo evento en paralelo pasaron juntas el chequeo
            // de arriba: la unique constraint es la que realmente decide.
            log.debug("Webhook duplicado detectado por la unique constraint: {}", providerEventId);
            return WebhookIngestOutcome.DUPLICATE;
        }
    }

    private String extractProviderEventId(String rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            JsonNode id = root.get("id");
            return id == null || !id.isTextual() ? null : id.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
