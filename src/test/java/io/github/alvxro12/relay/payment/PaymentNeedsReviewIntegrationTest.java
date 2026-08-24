package io.github.alvxro12.relay.payment;

import io.github.alvxro12.relay.auth.AuthTestSupport;
import io.github.alvxro12.relay.auth.Merchant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc y merchants los aporta {@link AuthTestSupport}, que ademas aplica la cadena
 * de Spring Security: sin eso el filtro no correria y estos tests probarian el scope
 * por merchant sobre un sistema abierto.
 *
 * <p>El merchant ya no es un UUID cualquiera: para pedir el listado hay que tener un
 * token, y para tener un token hay que ser una fila de merchants.
 */
class PaymentNeedsReviewIntegrationTest extends AuthTestSupport {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Los pagos en revisión son datos de un comercio. El endpoint devolvía los de
     * todos los merchants porque la consulta no tenía scope: cualquiera que llegara
     * al puerto leía los pagos sin resolver de todo el sistema.
     */
    @Test
    void needsReview_doesNotLeakPaymentsOfOtherMerchants() throws Exception {
        Merchant merchant = activeMerchant("s3cr3t-" + UUID.randomUUID());
        Merchant otherMerchant = activeMerchant("s3cr3t-" + UUID.randomUUID());
        UUID merchantId = merchant.getId();
        UUID otherMerchantId = otherMerchant.getId();

        Payment own = staleUnknownPayment(merchantId);
        Payment other = staleUnknownPayment(otherMerchantId);

        mockMvc.perform(get("/payments/needs-review").header(HttpHeaders.AUTHORIZATION, bearer(validTokenFor(merchant))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..id", hasItem(own.getId().toString())))
                .andExpect(jsonPath("$..id", not(hasItem(other.getId().toString()))));

        // Y al revés, para que no pase por un filtro que simplemente devuelve poco.
        mockMvc.perform(get("/payments/needs-review").header(HttpHeaders.AUTHORIZATION, bearer(validTokenFor(otherMerchant))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..id", hasItem(other.getId().toString())))
                .andExpect(jsonPath("$..id", not(hasItem(own.getId().toString()))));
    }

    /**
     * Los tres estados que pueden quedar colgados salen por el endpoint, cada uno contra
     * su propio umbral, y el scope por merchant sigue valiendo para los tres —no solo
     * para el UNKNOWN, que era el único que la consulta miraba—.
     */
    @Test
    void needsReview_returnsTheThreeStatesThatCanGetStuck() throws Exception {
        Merchant merchant = activeMerchant("s3cr3t-" + UUID.randomUUID());
        Merchant otherMerchant = activeMerchant("s3cr3t-" + UUID.randomUUID());
        UUID merchantId = merchant.getId();
        UUID otherMerchantId = otherMerchant.getId();

        Payment unknown = stalePayment(merchantId, PaymentStatus.UNKNOWN, null, 30, ChronoUnit.MINUTES);
        Payment processing = stalePayment(merchantId, PaymentStatus.PROCESSING, null, 30, ChronoUnit.MINUTES);
        Payment awaiting = stalePayment(merchantId, PaymentStatus.AWAITING_CONFIRMATION,
                "fake_txn_" + UUID.randomUUID(), 8, ChronoUnit.HOURS);

        // Mismo estado y misma edad que un UNKNOWN que sí sale, pero adentro de su propia
        // ventana: esperar un webhook media hora es el flujo feliz, no un pago colgado.
        Payment awaitingWithinThreshold = stalePayment(merchantId, PaymentStatus.AWAITING_CONFIRMATION,
                "fake_txn_" + UUID.randomUUID(), 30, ChronoUnit.MINUTES);

        // Los estados nuevos también son datos de un comercio.
        Payment otherMerchantProcessing = stalePayment(
                otherMerchantId, PaymentStatus.PROCESSING, null, 30, ChronoUnit.MINUTES);

        mockMvc.perform(get("/payments/needs-review").header(HttpHeaders.AUTHORIZATION, bearer(validTokenFor(merchant))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..id", hasItem(unknown.getId().toString())))
                .andExpect(jsonPath("$..id", hasItem(processing.getId().toString())))
                .andExpect(jsonPath("$..id", hasItem(awaiting.getId().toString())))
                .andExpect(jsonPath("$..id", not(hasItem(awaitingWithinThreshold.getId().toString()))))
                .andExpect(jsonPath("$..id", not(hasItem(otherMerchantProcessing.getId().toString()))));
    }

    /**
     * Sin token no hay merchant contra el cual filtrar. Antes esto era un 422 del
     * GlobalExceptionHandler porque faltaba un header; ahora lo corta la cadena de
     * filtros antes de llegar al controller y es un 401. Lo que no cambia es lo que
     * importa: nunca un listado sin scope.
     */
    @Test
    void needsReview_withoutToken_isRejected() throws Exception {
        mockMvc.perform(get("/payments/needs-review"))
                .andExpect(status().isUnauthorized());
    }

    /** Un pago en UNKNOWN lo bastante viejo como para entrar en la ventana por defecto. */
    private Payment staleUnknownPayment(UUID merchantId) {
        return stalePayment(merchantId, PaymentStatus.UNKNOWN, null, 30, ChronoUnit.MINUTES);
    }

    private Payment stalePayment(UUID merchantId, PaymentStatus status, String providerTransactionId,
                                 long age, ChronoUnit unit) {
        Payment payment = new Payment();
        payment.setMerchantId(merchantId);
        payment.setIdempotencyKey(UUID.randomUUID().toString());
        payment.setAmount(1000L);
        payment.setCurrency("USD");
        payment.setStatus(status);
        payment.setProviderTransactionId(providerTransactionId);
        Payment saved = paymentRepository.saveAndFlush(payment);

        // updated_at lo maneja la auditoría de JPA, así que se fuerza por SQL directo.
        jdbcTemplate.update(
                "UPDATE payments SET updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(age, unit)), saved.getId());

        return saved;
    }
}
