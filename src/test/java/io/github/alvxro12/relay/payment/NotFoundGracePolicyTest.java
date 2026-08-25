package io.github.alvxro12.relay.payment;

import io.github.alvxro12.relay.provider.ProviderPaymentStatus;
import io.github.alvxro12.relay.provider.ProviderProperties;
import io.github.alvxro12.relay.provider.ProviderStatusResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unitario y con el instante como parámetro: la guarda es una función pura de
 * (respuesta del proveedor, marca del intento de cobro, ahora).
 */
class NotFoundGracePolicyTest {

    private static final Duration GRACE = Duration.ofMinutes(30);
    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");

    private final NotFoundGracePolicy policy = new NotFoundGracePolicy(new ProviderProperties(GRACE));

    /**
     * El test que justifica que la gracia sean 30 minutos y no 15.
     *
     * <p>Este pago lleva 20 minutos: ya pasó {@code stale-after.unknown} (15m), así que la
     * reconciliación <b>lo está mirando</b>, y el proveedor contesta que no conoce el
     * cobro. Con la gracia en 15m —el valor que parecía razonable— la ventana ya habría
     * vencido para cuando la reconciliación lo mira por primera vez, y la guarda no habría
     * frenado nunca nada: sería decoración.
     *
     * <p>Los 20 minutos están elegidos para caer justo en ese hueco: stale para la
     * reconciliación, todavía dentro de la gracia.
     */
    @Test
    void aStalePaymentStillWithinTheGraceWindowIsNotAuthorisedToFail() {
        Payment payment = chargeAttemptedAgo(Duration.ofMinutes(20));

        assertThat(policy.authorizesFailing(notFound(), payment, NOW))
                .as("stale para la reconciliación, pero todavía dentro de la gracia")
                .isFalse();
    }

    @Test
    void pastTheGraceWindowANotFoundIsAuthorisedToFail() {
        Payment payment = chargeAttemptedAgo(GRACE.plusMinutes(1));

        assertThat(policy.authorizesFailing(notFound(), payment, NOW)).isTrue();
    }

    /**
     * Deja escrito por qué la gracia no puede quedar a la altura de
     * {@code stale-after.unknown}. Las dos ventanas se miden desde casi el mismo instante,
     * así que con 15m la gracia vence justo cuando la reconciliación mira el pago por
     * primera vez: el mismo pago de 20 minutos queda autorizado, y la guarda pasa a ser
     * decoración.
     */
    @Test
    void aGraceAsShortAsTheStaleThresholdWouldNotStopAnything() {
        Payment payment = chargeAttemptedAgo(Duration.ofMinutes(20));
        NotFoundGracePolicy tooShort = new NotFoundGracePolicy(
                new ProviderProperties(Duration.ofMinutes(15)));

        assertThat(tooShort.authorizesFailing(notFound(), payment, NOW))
                .as("con la gracia en 15m el pago ya estaría autorizado a fallar")
                .isTrue();
        assertThat(policy.authorizesFailing(notFound(), payment, NOW))
                .as("con 30m, no")
                .isFalse();
    }

    /** El borde exacto cuenta como cumplido: la ventana es "al menos", no "más de". */
    @Test
    void exactlyAtTheGraceWindowIsAuthorised() {
        Payment payment = chargeAttemptedAgo(GRACE);

        assertThat(policy.authorizesFailing(notFound(), payment, NOW)).isTrue();
    }

    @Test
    void aChargeAttemptedJustNowIsNeverAuthorised() {
        Payment payment = chargeAttemptedAgo(Duration.ZERO);

        assertThat(policy.authorizesFailing(notFound(), payment, NOW)).isFalse();
    }

    /**
     * Ninguna otra respuesta autoriza a fallar un pago, por viejo que sea. UNKNOWN es el
     * que más importa: el proveedor tiene el cobro registrado y no sabe cómo terminó, así
     * que darlo por fallido sería inventar un desenlace.
     */
    @Test
    void noOtherProviderAnswerAuthorisesFailing() {
        Payment ancient = chargeAttemptedAgo(Duration.ofDays(30));

        for (ProviderPaymentStatus status : ProviderPaymentStatus.values()) {
            if (status == ProviderPaymentStatus.NOT_FOUND) {
                continue;
            }
            assertThat(policy.authorizesFailing(new ProviderStatusResult(status, "fake_txn"), ancient, NOW))
                    .as("respuesta %s", status)
                    .isFalse();
        }
    }

    /**
     * Un pago sin marca de intento nunca llegó al proveedor, así que no hay ventana que
     * medir y no hay nada que dar por fallido.
     */
    @Test
    void aPaymentThatWasNeverChargedIsNotAuthorised() {
        Payment neverClaimed = new Payment();
        neverClaimed.setMerchantId(UUID.randomUUID());

        assertThat(neverClaimed.getChargeAttemptedAt()).isNull();
        assertThat(policy.authorizesFailing(notFound(), neverClaimed, NOW)).isFalse();
    }

    @Test
    void aNullInquiryIsNotAuthorised() {
        assertThat(policy.authorizesFailing(null, chargeAttemptedAgo(Duration.ofDays(1)), NOW)).isFalse();
    }

    /**
     * La marca se escribe una sola vez. Si un segundo claim la moviera, la ventana de
     * gracia se reiniciaría con ella y el pago no la cumpliría nunca.
     */
    @Test
    void theChargeAttemptMarkIsWrittenOnlyOnce() {
        Payment payment = new Payment();
        Instant first = NOW.minus(Duration.ofHours(2));

        payment.markChargeAttempted(first);
        payment.markChargeAttempted(NOW);

        assertThat(payment.getChargeAttemptedAt()).isEqualTo(first);
        assertThat(policy.authorizesFailing(notFound(), payment, NOW))
                .as("la segunda marca no pudo reiniciar la gracia")
                .isTrue();
    }

    private static ProviderStatusResult notFound() {
        return new ProviderStatusResult(ProviderPaymentStatus.NOT_FOUND, null);
    }

    private static Payment chargeAttemptedAgo(Duration ago) {
        Payment payment = new Payment();
        payment.setMerchantId(UUID.randomUUID());
        payment.setStatus(PaymentStatus.UNKNOWN);
        payment.markChargeAttempted(NOW.minus(ago));
        return payment;
    }
}
