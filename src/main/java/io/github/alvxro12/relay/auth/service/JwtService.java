package io.github.alvxro12.relay.auth.service;

import io.github.alvxro12.relay.auth.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Emite y (desde la fase C) valida los JWT de acceso. HS256 con clave simétrica:
 * el emisor y el validador son el mismo servicio, así que no hay ninguna ventaja
 * en un par asimétrico y sí el costo de gestionarlo.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final String issuer;
    private final String audience;
    private final Duration ttl;

    public JwtService(JwtProperties properties) {
        this.key = buildKey(properties.secret());
        this.issuer = require(properties.issuer(), "relay.auth.jwt.issuer");
        this.audience = require(properties.audience(), "relay.auth.jwt.audience");
        this.ttl = properties.ttl();

        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("relay.auth.jwt.ttl debe ser una duración positiva");
        }
    }

    /**
     * Firma un token para el merchant. Los claims son exactamente los seis del
     * diseño: sub, iss, aud, exp, iat, jti. Nada de nombre, estado ni permisos —
     * un JWT es legible por cualquiera que lo tenga (está firmado, no cifrado) y
     * todo claim de más es información filtrada y una fuente de verdad duplicada
     * que puede quedar vieja respecto de la DB.
     */
    public IssuedToken issue(UUID merchantId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);

        String token = Jwts.builder()
                .subject(merchantId.toString())
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                // jti: identificador único del token. No se persiste (no hay lista de
                // revocación), pero permite correlacionar en logs un token concreto
                // sin loguear el token.
                .id(UUID.randomUUID().toString())
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        return new IssuedToken(token, ttl.toSeconds());
    }

    /**
     * Decodifica el secreto y falla en el arranque si no llega a 256 bits. HS256 con
     * una clave más corta que su salida es criptográficamente más débil de lo que
     * aparenta, y es el tipo de error que no se nota nunca en runtime.
     */
    private static SecretKey buildKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "relay.auth.jwt.secret no está definido (variable de entorno JWT_SECRET)");
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("relay.auth.jwt.secret debe ser Base64 válido", e);
        }

        if (decoded.length < 32) {
            throw new IllegalStateException(
                    "relay.auth.jwt.secret debe tener al menos 32 bytes (256 bits) decodificados, tiene "
                            + decoded.length);
        }

        return Keys.hmacShaKeyFor(decoded);
    }

    private static String require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " no está definido");
        }
        return value;
    }

    /** El token firmado y cuánto le queda de vida, que es lo que devuelve el endpoint. */
    public record IssuedToken(String token, long expiresInSeconds) {
    }
}
