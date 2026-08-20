package io.github.alvxro12.relay.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

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
 * MockMvc construido a mano sobre el WebApplicationContext, igual que
 * WebhookIntegrationTest: con {@code webEnvironment = RANDOM_PORT} habría un segundo
 * contexto de Spring y sus @RabbitListener competirían por las mismas colas.
 */
@SpringBootTest
class PaymentNeedsReviewIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    /**
     * Los pagos en revisión son datos de un comercio. El endpoint devolvía los de
     * todos los merchants porque la consulta no tenía scope: cualquiera que llegara
     * al puerto leía los pagos sin resolver de todo el sistema.
     */
    @Test
    void needsReview_doesNotLeakPaymentsOfOtherMerchants() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID otherMerchantId = UUID.randomUUID();

        Payment own = staleUnknownPayment(merchantId);
        Payment other = staleUnknownPayment(otherMerchantId);

        mockMvc.perform(get("/payments/needs-review").header("X-Merchant-Id", merchantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..id", hasItem(own.getId().toString())))
                .andExpect(jsonPath("$..id", not(hasItem(other.getId().toString()))));

        // Y al revés, para que no pase por un filtro que simplemente devuelve poco.
        mockMvc.perform(get("/payments/needs-review").header("X-Merchant-Id", otherMerchantId))
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
        UUID merchantId = UUID.randomUUID();
        UUID otherMerchantId = UUID.randomUUID();

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

        mockMvc.perform(get("/payments/needs-review").header("X-Merchant-Id", merchantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..id", hasItem(unknown.getId().toString())))
                .andExpect(jsonPath("$..id", hasItem(processing.getId().toString())))
                .andExpect(jsonPath("$..id", hasItem(awaiting.getId().toString())))
                .andExpect(jsonPath("$..id", not(hasItem(awaitingWithinThreshold.getId().toString()))))
                .andExpect(jsonPath("$..id", not(hasItem(otherMerchantProcessing.getId().toString()))));
    }

    /**
     * Sin el header no hay merchant contra el cual filtrar, así que la request no
     * puede resolverse: 422 vía GlobalExceptionHandler, nunca un listado sin scope.
     */
    @Test
    void needsReview_withoutMerchantHeader_isRejected() throws Exception {
        mockMvc.perform(get("/payments/needs-review"))
                .andExpect(status().isUnprocessableEntity());
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
