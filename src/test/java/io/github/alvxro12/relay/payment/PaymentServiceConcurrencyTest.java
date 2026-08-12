package io.github.alvxro12.relay.payment;

import io.github.alvxro12.relay.payment.dto.CreatePaymentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PaymentServiceConcurrencyTest {

    @Autowired
    private Environment env;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void twoConcurrentRequestsWithSameIdempotencyKey_produceOnlyOnePayment() throws InterruptedException {

        UUID merchantId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        CreatePaymentRequest request = new CreatePaymentRequest(1050L, "USD", "order-test");

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount); // los hilos avisan "estoy listo"
        CountDownLatch startLatch = new CountDownLatch(1);           // la señal de arranque simultáneo
        CountDownLatch doneLatch = new CountDownLatch(threadCount);  // esperamos a que ambos terminen

        AtomicReference<PaymentResult> result1 = new AtomicReference<>();
        AtomicReference<PaymentResult> result2 = new AtomicReference<>();

        executor.submit(() -> {
            readyLatch.countDown();
            await(startLatch);
            result1.set(paymentService.createPayment(merchantId, idempotencyKey, request));
            doneLatch.countDown();
        });

        executor.submit(() -> {
            readyLatch.countDown();
            await(startLatch);
            result2.set(paymentService.createPayment(merchantId, idempotencyKey, request));
            doneLatch.countDown();
        });

        readyLatch.await();                    // esperamos que ambos hilos estén listos
        startLatch.countDown();                 // ¡ya! arrancan los dos a la vez
        doneLatch.await(10, TimeUnit.SECONDS);  // esperamos a que ambos terminen
        executor.shutdown();

        // La aserción central: en la base de datos solo debe existir UN pago
        List<Payment> allPayments = paymentRepository.findAll();
        long matchingPayments = allPayments.stream()
                .filter(p -> p.getMerchantId().equals(merchantId))
                .count();

        assertThat(matchingPayments).isEqualTo(1);

        // Bonus: uno de los dos resultados debe ser CREATED, el otro REPLAY
        List<PaymentResult.Outcome> outcomes = List.of(result1.get().outcome(), result2.get().outcome());
        assertThat(outcomes).containsExactlyInAnyOrder(
                PaymentResult.Outcome.CREATED,
                PaymentResult.Outcome.REPLAY
        );
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
