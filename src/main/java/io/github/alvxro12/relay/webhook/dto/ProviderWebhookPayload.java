package io.github.alvxro12.relay.webhook.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Contrato del cuerpo que manda el proveedor:
 *
 * <pre>
 * { "id": "evt_123", "type": "payment.succeeded", "data": { "providerTransactionId": "txn_456" } }
 * </pre>
 *
 * Se ignoran los campos desconocidos a propósito: los proveedores agregan campos
 * sin avisar y un evento nuevo no debería romper el consumer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderWebhookPayload(
        String id,
        String type,
        Data data
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(String providerTransactionId) {
    }

    public String providerTransactionId() {
        return data == null ? null : data.providerTransactionId();
    }
}
