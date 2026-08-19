package io.github.alvxro12.relay.webhook.controller;

import io.github.alvxro12.relay.webhook.service.WebhookIngestService;
import io.github.alvxro12.relay.webhook.service.WebhookSignatureVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private final WebhookSignatureVerifier signatureVerifier;
    private final WebhookIngestService ingestService;

    public WebhookController(WebhookSignatureVerifier signatureVerifier,
                             WebhookIngestService ingestService) {
        this.signatureVerifier = signatureVerifier;
        this.ingestService = ingestService;
    }

    /**
     * El cuerpo se recibe como String crudo a propósito: el HMAC se calcula sobre
     * los bytes exactos que mandó el proveedor, y deserializar a un DTO y volver a
     * serializar cambiaría esos bytes (orden de claves, espacios) invalidando la firma.
     */
    @PostMapping("/provider")
    public ResponseEntity<Void> receive(
            @RequestHeader(name = WebhookSignatureVerifier.SIGNATURE_HEADER, required = false) String signature,
            @RequestBody String rawPayload
    ) {
        // Autenticación primero, que es barata: una firma inválida corta acá sin
        // tocar la DB ni la cola.
        if (!signatureVerifier.isValid(rawPayload, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // A partir de acá se responde rápido: el proveedor reintenta agresivamente
        // ante timeouts, así que la validación de negocio queda para el consumer.
        return switch (ingestService.ingest(rawPayload)) {
            // Duplicado responde 200 igual que un evento nuevo: cualquier cosa que
            // no sea 2xx haría que el proveedor lo siga reintentando.
            case ACCEPTED, DUPLICATE -> ResponseEntity.ok().build();

            case UNPARSEABLE -> ResponseEntity.badRequest().build();
        };
    }
}
