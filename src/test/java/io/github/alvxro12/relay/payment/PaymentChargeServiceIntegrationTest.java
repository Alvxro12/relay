package io.github.alvxro12.relay.payment;

import io.github.alvxro12.relay.payment.dto.CreatePaymentRequest;
import io.github.alvxro12.relay.payment.service.PaymentService;
import io.github.alvxro12.relay.provider.ChargeStatus;
import io.github.alvxro12.relay.provider.FakePaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
class PaymentChargeServiceIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private FakePaymentProvider fakePaymentProvider;

    @BeforeEach
    void resetFakeProvider() {
        // Bean singleton: sin este reset, un test anterior podría dejar forzado
        // un resultado que contamine el siguiente.
        fakePaymentProvider.forceNextResult(ChargeStatus.SUCCESS);
    }

    @Test
    void success_marksPaymentAsSucceeded() {
        assertFinalStatus(ChargeStatus.SUCCESS, PaymentStatus.SUCCEEDED);
    }

    @Test
    void declined_marksPaymentAsFailed() {
        assertFinalStatus(ChargeStatus.DECLINED, PaymentStatus.FAILED);
    }

    @Test
    void serverError_marksPaymentAsUnknown() {
        assertFinalStatus(ChargeStatus.SERVER_ERROR, PaymentStatus.UNKNOWN);
    }

    @Test
    void timeout_marksPaymentAsUnknown() {
        assertFinalStatus(ChargeStatus.TIMEOUT, PaymentStatus.UNKNOWN);
    }

    private void assertFinalStatus(ChargeStatus providerResult, PaymentStatus expectedStatus) {
        fakePaymentProvider.forceNextResult(providerResult);

        UUID merchantId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        CreatePaymentRequest request = new CreatePaymentRequest(1000L, "USD", "order-" + idempotencyKey);

        PaymentResult result = paymentService.createPayment(merchantId, idempotencyKey, request);
        UUID paymentId = result.payment().getId();

        // El consumer procesa el evento de forma asíncrona vía RabbitListener;
        // esperamos activamente el estado real en BD en vez de asumir timing fijo.
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    Payment payment = paymentRepository.findById(paymentId).orElseThrow();
                    assertThat(payment.getStatus()).isEqualTo(expectedStatus);
                });
    }
}
