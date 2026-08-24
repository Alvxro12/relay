package io.github.alvxro12.relay.auth;

import com.jayway.jsonpath.JsonPath;
import io.github.alvxro12.relay.payment.Payment;
import io.github.alvxro12.relay.payment.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El Definition of Done del gate: un merchant autenticado no puede tocar los datos de
 * otro <b>aunque conozca el UUID</b>.
 */
class MerchantIsolationIntegrationTest extends AuthTestSupport {

    @Autowired
    private PaymentRepository paymentRepository;

    /**
     * A conoce el UUID exacto del pago de B y pide ese pago con su propio token válido.
     *
     * <p>404 y no 403: un 403 confirmaría que ese UUID existe, y con eso se enumera qué
     * pagos hay en el sistema sin poder leer ninguno. Para A, el pago de B y un pago
     * inexistente son lo mismo.
     */
    @Test
    void merchantCannotReadPaymentOfAnother_evenKnowingTheExactId() throws Exception {
        Merchant merchantA = activeMerchant("s3cr3t-" + UUID.randomUUID());
        Merchant merchantB = activeMerchant("s3cr3t-" + UUID.randomUUID());

        UUID paymentOfB = createPayment(merchantB, UUID.randomUUID().toString());

        // B sí lo ve: si esto fallara, el 404 de abajo no probaría aislamiento sino
        // que el endpoint está roto.
        mockMvc.perform(get("/payments/" + paymentOfB)
                        .header(HttpHeaders.AUTHORIZATION, bearer(validTokenFor(merchantB))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(paymentOfB.toString()));

        mockMvc.perform(get("/payments/" + paymentOfB)
                        .header(HttpHeaders.AUTHORIZATION, bearer(validTokenFor(merchantA))))
                .andExpect(status().isNotFound());

        // Y el pago sigue existiendo y siendo de B: el 404 es una respuesta, no un borrado.
        Payment stored = paymentRepository.findById(paymentOfB).orElseThrow();
        assertThat(stored.getMerchantId()).isEqualTo(merchantB.getId());
    }

    /**
     * La Idempotency-Key es del cliente, así que dos merchants pueden elegir la misma
     * sin saberlo. La unicidad es (merchant_id, idempotency_key): son dos pagos
     * independientes, no un replay.
     *
     * <p>Con una unique global sobre la key sola, el pago de B rebotaría contra el de A
     * y B recibiría el pago de otro comercio como si fuera suyo.
     */
    @Test
    void sameIdempotencyKeyFromTwoMerchants_createsTwoIndependentPayments() throws Exception {
        Merchant merchantA = activeMerchant("s3cr3t-" + UUID.randomUUID());
        Merchant merchantB = activeMerchant("s3cr3t-" + UUID.randomUUID());

        String sharedKey = "shared-key-" + UUID.randomUUID();

        UUID paymentOfA = createPayment(merchantA, sharedKey);
        UUID paymentOfB = createPayment(merchantB, sharedKey);

        assertThat(paymentOfA).isNotEqualTo(paymentOfB);
        assertThat(paymentRepository.findById(paymentOfA).orElseThrow().getMerchantId())
                .isEqualTo(merchantA.getId());
        assertThat(paymentRepository.findById(paymentOfB).orElseThrow().getMerchantId())
                .isEqualTo(merchantB.getId());

        // Y cada uno solo ve el suyo.
        mockMvc.perform(get("/payments/" + paymentOfA)
                        .header(HttpHeaders.AUTHORIZATION, bearer(validTokenFor(merchantB))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/payments/" + paymentOfB)
                        .header(HttpHeaders.AUTHORIZATION, bearer(validTokenFor(merchantA))))
                .andExpect(status().isNotFound());
    }

    /**
     * Repetir la misma key con el mismo token sí es un replay: mismo pago, 200 en vez
     * de 201. Está acá para que el test de arriba no pueda dar verde por haberse roto
     * la idempotencia entera.
     */
    @Test
    void sameIdempotencyKeyFromTheSameMerchant_isStillAReplay() throws Exception {
        Merchant merchant = activeMerchant("s3cr3t-" + UUID.randomUUID());
        String key = "replay-key-" + UUID.randomUUID();

        UUID first = createPayment(merchant, key);

        MvcResult replay = mockMvc.perform(post("/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(validTokenFor(merchant)))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(UUID.fromString(JsonPath.read(replay.getResponse().getContentAsString(), "$.id")))
                .isEqualTo(first);
    }

    /** El listado de revisión tampoco cruza merchants. */
    @Test
    void needsReviewIsScopedToTheMerchantOfTheToken() throws Exception {
        Merchant merchantA = activeMerchant("s3cr3t-" + UUID.randomUUID());
        Merchant merchantB = activeMerchant("s3cr3t-" + UUID.randomUUID());

        UUID paymentOfB = createPayment(merchantB, UUID.randomUUID().toString());

        // olderThanMinutes=0 mete cualquier pago no terminal en la ventana, así que si
        // hubiera fuga aparecería acá.
        mockMvc.perform(get("/payments/needs-review?olderThanMinutes=0")
                        .header(HttpHeaders.AUTHORIZATION, bearer(validTokenFor(merchantA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..id",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(paymentOfB.toString()))));
    }

    private UUID createPayment(Merchant merchant, String idempotencyKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(validTokenFor(merchant)))
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody()))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private static String paymentBody() {
        return "{\"amount\":1050,\"currency\":\"USD\",\"reference\":\"isolation-test\"}";
    }
}
