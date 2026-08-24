package io.github.alvxro12.relay.auth.service;

import io.github.alvxro12.relay.auth.InvalidTokenException;
import io.github.alvxro12.relay.auth.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.security.Key;
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
     * Valida el token y devuelve el merchantId del claim {@code sub}.
     *
     * Lo que exige, en este orden: que el header traiga exactamente {@code alg: HS256}
     * (ver {@link Hs256KeyLocator}), que la firma verifique contra la clave, que
     * {@code exp} no haya pasado, y que {@code iss} y {@code aud} sean los nuestros.
     * Cualquiera de esas que falle sale por el mismo InvalidTokenException.
     *
     * <p>Los dos últimos no son ceremonia: sin {@code aud}, un token emitido por este
     * mismo servicio para otro consumidor sería aceptado acá, y sin {@code iss} lo
     * sería cualquier token firmado con una clave que se reuse en otro sistema.
     */
    public UUID parseMerchantId(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .keyLocator(new Hs256KeyLocator(key))
                    .requireIssuer(issuer)
                    .requireAudience(audience)
                    // Sin tolerancia de reloj: emisor y validador son el mismo proceso,
                    // así que no hay desfasaje que compensar y cada segundo de gracia es
                    // un segundo más de vida para un token que ya venció.
                    .clockSkewSeconds(0)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            // El mensaje de jjwt no incluye el token, pero sí el motivo, que es lo que
            // sirve para diagnosticar. Va al log del filtro en DEBUG, nunca a la respuesta.
            throw new InvalidTokenException(e.getMessage(), e);
        }

        try {
            return UUID.fromString(claims.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            // Firmado por nosotros pero con un sub que no es un UUID: no debería pasar
            // nunca, y si pasa es un bug de emisión, no un token de un atacante.
            throw new InvalidTokenException("sub no es un UUID", e);
        }
    }

    /**
     * Fija el algoritmo en vez de aceptar el que declare el token.
     *
     * jjwt ya rechaza {@code alg: none} por su cuenta, pero {@code verifyWith(key)} a
     * secas acepta cualquier HMAC: un token con {@code alg: HS512} firmado con la misma
     * clave pasaría. Acá el header se compara contra HS256 <b>antes</b> de devolver la
     * clave, así que el algoritmo lo decide el servidor y no el que manda el token.
     */
    private record Hs256KeyLocator(SecretKey key) implements Locator<Key> {

        @Override
        public Key locate(Header header) {
            String algorithm = header.getAlgorithm();
            if (!Jwts.SIG.HS256.getId().equals(algorithm)) {
                throw new InvalidTokenException("algoritmo no permitido: " + algorithm);
            }
            return key;
        }
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
