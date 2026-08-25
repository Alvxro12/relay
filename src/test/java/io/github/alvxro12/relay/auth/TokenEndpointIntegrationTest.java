package io.github.alvxro12.relay.auth;

import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /v1/oauth/token: qué se emite y, sobre todo, qué no se filtra al rechazar. */
class TokenEndpointIntegrationTest extends AuthTestSupport {

    @Value("${relay.auth.jwt.secret}")
    private String jwtSecret;

    @Test
    void token_withValidCredentials_returnsJwtWithExactlyTheSixClaims() throws Exception {
        String secret = "s3cr3t-" + UUID.randomUUID();
        Merchant merchant = activeMerchant(secret);

        MvcResult result = mockMvc.perform(tokenRequest(merchant.getClientId(), secret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                // Un token en un caché intermedio es un token robado.
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();

        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");

        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtSecret.trim())))
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertThat(claims.getSubject()).isEqualTo(merchant.getId().toString());
        assertThat(claims.getIssuer()).isEqualTo(issuer);
        assertThat(claims.getAudience()).containsExactly(audience);
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();

        // Ni uno más. Un JWT está firmado, no cifrado: todo claim de más es información
        // que viaja legible y una fuente de verdad duplicada que puede quedar vieja.
        assertThat(claims.keySet())
                .containsExactlyInAnyOrder("sub", "iss", "aud", "exp", "iat", "jti");
    }

    /**
     * Los tres modos de falla tienen que dar exactamente la misma respuesta. Si un
     * clientId inexistente se distinguiera de un secret incorrecto, el endpoint sería
     * un oráculo para enumerar qué merchants existen.
     */
    @Test
    void token_failuresAreIndistinguishable() throws Exception {
        String secret = "s3cr3t-" + UUID.randomUUID();
        Merchant active = activeMerchant(secret);
        Merchant disabled = disabledMerchant(secret);

        // clientId que no existe
        mockMvc.perform(tokenRequest("mch_test_noexiste", secret))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"invalid_client\"}", true));

        // clientId real, secret incorrecto
        mockMvc.perform(tokenRequest(active.getClientId(), "secret-equivocado"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"invalid_client\"}", true));

        // credenciales correctas pero merchant deshabilitado
        mockMvc.perform(tokenRequest(disabled.getClientId(), secret))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"invalid_client\"}", true));
    }

    /** Un merchant DISABLED no consigue tokens nuevos, aunque su secret sea correcto. */
    @Test
    void token_forDisabledMerchant_isRejected() throws Exception {
        String secret = "s3cr3t-" + UUID.randomUUID();
        Merchant merchant = disabledMerchant(secret);

        mockMvc.perform(tokenRequest(merchant.getClientId(), secret))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Sexto intento en el mismo minuto desde la misma IP: 429. El límite existe porque
     * cada intento cuesta un BCrypt cost 12, así que sin él la fuerza bruta es un ataque
     * de agotamiento de CPU aunque nunca acierte.
     */
    @Test
    void token_sixthAttemptInTheSameMinute_isRateLimited() throws Exception {
        String secret = "s3cr3t-" + UUID.randomUUID();
        Merchant merchant = activeMerchant(secret);

        // IP propia y fija para este test: es la dimensión que se está probando.
        String ip = "203.0.113." + (Math.abs(UUID.randomUUID().hashCode()) % 200 + 1);

        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(post("/v1/oauth/token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(merchant.getClientId(), "secret-equivocado"))
                            .with(remoteAddress(ip)))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/v1/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(merchant.getClientId(), secret))   // ahora con el secret bueno
                        .with(remoteAddress(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().json("{\"error\":\"too_many_requests\"}", true));
    }

    /**
     * Un cuerpo que Jackson no puede leer sale por el mismo 401 que unas credenciales
     * incorrectas y no por el handler genérico, que loguearía el stacktrace de un
     * request que lleva un clientSecret adentro.
     */
    @Test
    void token_withUnreadableBody_returnsInvalidClientWithoutLeaking() throws Exception {
        mockMvc.perform(post("/v1/oauth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ esto no es json ")
                        .with(uniqueRemoteAddress()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"invalid_client\"}", true));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder tokenRequest(
            String clientId, String clientSecret) {
        return post("/v1/oauth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(clientId, clientSecret))
                .with(uniqueRemoteAddress());
    }

    private static String body(String clientId, String clientSecret) {
        return "{\"clientId\":\"" + clientId + "\",\"clientSecret\":\"" + clientSecret + "\"}";
    }
}
