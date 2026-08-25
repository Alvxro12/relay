package io.github.alvxro12.relay.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuración del JWT. El secreto llega por variable de entorno (ver .env.example);
 * no hay default ni valor de respaldo a propósito: sin {@code JWT_SECRET} la app no
 * arranca, que es mejor que arrancar firmando con una clave conocida.
 *
 * @param secret   clave HMAC en Base64. Debe decodificar a 32 bytes o más (256 bits).
 * @param issuer   valor del claim {@code iss}, exigido al validar.
 * @param audience valor del claim {@code aud}, exigido al validar.
 * @param ttl      vida del token. Corta a propósito: es el único mecanismo de
 *                 revocación que hay, porque no se guarda estado de sesión.
 */
@ConfigurationProperties(prefix = "relay.auth.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        String audience,
        Duration ttl
) {
}
