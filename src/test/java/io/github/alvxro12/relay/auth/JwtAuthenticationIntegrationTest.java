package io.github.alvxro12.relay.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.junit.jupiter.api.DisplayName;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Qué acepta y qué rechaza el filtro. Todos los rechazos usan el mismo endpoint
 * ({@code GET /payments/needs-review}) para que la única variable sea el token.
 */
class JwtAuthenticationIntegrationTest extends AuthTestSupport {

    private static final String PROTECTED_ENDPOINT = "/payments/needs-review";

    @Test
    void withValidToken_isAllowed() throws Exception {
        Merchant merchant = activeMerchant("s3cr3t-" + UUID.randomUUID());

        mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, bearer(validTokenFor(merchant))))
                .andExpect(status().isOk());
    }

    /**
     * Sin header no lo corta el filtro sino el AuthorizationFilter, y aun así la
     * respuesta tiene que ser idéntica a la de un token inválido: si "sin token" y
     * "token inválido" se distinguieran, ya sería información.
     */
    @Test
    void withoutToken_isUnauthorized() throws Exception {
        mockMvc.perform(get(PROTECTED_ENDPOINT))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"invalid_token\"}", true));
    }

    @Test
    void withExpiredToken_isUnauthorized() throws Exception {
        Merchant merchant = activeMerchant("s3cr3t-" + UUID.randomUUID());

        mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, bearer(expiredTokenFor(merchant))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"invalid_token\"}", true));
    }

    @Test
    void withTokenSignedByAnotherKey_isUnauthorized() throws Exception {
        Merchant merchant = activeMerchant("s3cr3t-" + UUID.randomUUID());

        mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenSignedWithAnotherKey(merchant))))
                .andExpect(status().isUnauthorized());
    }

    /**
     * El ataque de confusión de algoritmo: un token con {@code alg: none} y sin firma.
     * Si el parser tomara el algoritmo del header en vez de fijarlo, esto pasaría, y
     * cualquiera podría emitirse un token para el merchantId que quisiera.
     */
    @Test
    @DisplayName("alg: none es rechazado")
    void withAlgNoneToken_isUnauthorized() throws Exception {
        Merchant merchant = activeMerchant("s3cr3t-" + UUID.randomUUID());

        mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, bearer(algNoneTokenFor(merchant))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"invalid_token\"}", true));
    }

    /** Firmado por nosotros y vigente, pero emitido para otro destinatario. */
    @Test
    void withTokenForAnotherAudience_isUnauthorized() throws Exception {
        Merchant merchant = activeMerchant("s3cr3t-" + UUID.randomUUID());

        mockMvc.perform(get(PROTECTED_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenForAnotherAudience(merchant))))
                .andExpect(status().isUnauthorized());
    }

    /**
     * El token sigue siendo válido —firma y exp intactos— pero el merchant pasó a
     * DISABLED. Sin la relectura de la fila en cada request, deshabilitar un merchant
     * no surtiría efecto hasta que expire el último token que emitió.
     */
    @Test
    void withValidTokenOfAMerchantDisabledAfterwards_isUnauthorized() throws Exception {
        Merchant merchant = activeMerchant("s3cr3t-" + UUID.randomUUID());
        String token = validTokenFor(merchant);

        // El token ya funciona antes de deshabilitar: si no, el test podría dar verde
        // por cualquier otro motivo.
        mockMvc.perform(get(PROTECTED_ENDPOINT).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        merchant.setStatus(MerchantStatus.DISABLED);
        merchantRepository.saveAndFlush(merchant);

        mockMvc.perform(get(PROTECTED_ENDPOINT).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"invalid_token\"}", true));
    }

    /** Un header que no arranca con "Bearer " no autentica a nadie. */
    @Test
    void withMalformedAuthorizationHeader_isUnauthorized() throws Exception {
        mockMvc.perform(get(PROTECTED_ENDPOINT).header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
    }
}
