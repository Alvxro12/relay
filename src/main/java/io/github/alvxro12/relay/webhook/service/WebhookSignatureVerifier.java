package io.github.alvxro12.relay.webhook.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class WebhookSignatureVerifier {

    public static final String SIGNATURE_HEADER = "X-Signature";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public WebhookSignatureVerifier(@Value("${relay.webhook.secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Verifica el HMAC-SHA256 hex del cuerpo crudo. Es lo primero que corre en el
     * endpoint: si falla, no se toca ni la DB ni la cola.
     */
    public boolean isValid(String rawPayload, String signatureHeader) {
        if (rawPayload == null || signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }

        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signatureHeader.trim());
        } catch (IllegalArgumentException e) {
            return false; // no es hex válido: firma inválida, no un error del servidor
        }

        // Comparación en tiempo constante: un equals() común filtra por timing
        // cuántos bytes del prefijo acertó quien está probando firmas.
        return MessageDigest.isEqual(hmac(rawPayload), provided);
    }

    /** Firma un cuerpo con el mismo esquema que se espera del proveedor. */
    public String sign(String rawPayload) {
        return HexFormat.of().formatHex(hmac(rawPayload));
    }

    private byte[] hmac(String rawPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular el HMAC del webhook", e);
        }
    }
}
