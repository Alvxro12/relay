package io.github.alvxro12.relay.provider;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unitario y sin Spring: el proveedor simulado es una clase con una propiedad, y lo que hay
 * que probar es la coherencia entre lo que {@code charge()} le devuelve a Relay y lo que
 * {@code getPaymentStatus()} dice que pasó de verdad.
 */
class FakePaymentProviderTest {

    private final FakePaymentProvider provider = new FakePaymentProvider(new SandboxProperties(true));

    // --- los cinco escenarios ---------------------------------------------------

    @Test
    void success_isChargedAndReportedAsSucceeded() {
        UUID paymentId = UUID.randomUUID();

        ChargeResult result = charge(paymentId, SandboxScenario.SUCCESS);

        assertThat(result.status()).isEqualTo(ChargeStatus.SUCCESS);
        assertThat(result.providerTransactionId()).isEqualTo(FakePaymentProvider.transactionIdFor(paymentId));

        assertThat(provider.getPaymentStatus(paymentId))
                .isEqualTo(new ProviderStatusResult(
                        ProviderPaymentStatus.SUCCEEDED, result.providerTransactionId()));
    }

    /**
     * Rechazado no es una falla: el cobro ocurrió, el proveedor lo tiene registrado y lo
     * informa igual si se le pregunta. Si devolviera NOT_FOUND, la reconciliación no podría
     * distinguir "lo rechazaron" de "nunca llegó".
     */
    @Test
    void failed_isChargedAndReportedAsFailed() {
        UUID paymentId = UUID.randomUUID();

        ChargeResult result = charge(paymentId, SandboxScenario.FAILED);

        assertThat(result.status()).isEqualTo(ChargeStatus.DECLINED);
        assertThat(provider.getPaymentStatus(paymentId).status()).isEqualTo(ProviderPaymentStatus.FAILED);
    }

    @Test
    void awaitingConfirmation_isAcceptedAndReportedAsPending() {
        UUID paymentId = UUID.randomUUID();

        ChargeResult result = charge(paymentId, SandboxScenario.AWAITING_CONFIRMATION);

        assertThat(result.status()).isEqualTo(ChargeStatus.ACCEPTED);
        assertThat(result.providerTransactionId()).isNotNull();
        assertThat(provider.getPaymentStatus(paymentId).status()).isEqualTo(ProviderPaymentStatus.PENDING);
    }

    /** La llamada nunca llegó: no queda nada del lado del proveedor. */
    @Test
    void timeout_throwsAndLeavesNothingRecorded() {
        UUID paymentId = UUID.randomUUID();

        assertThatThrownBy(() -> charge(paymentId, SandboxScenario.TIMEOUT))
                .isInstanceOf(PaymentProviderTimeoutException.class);

        assertThat(provider.getPaymentStatus(paymentId))
                .as("un cobro que nunca ocurrió no existe para el proveedor")
                .isEqualTo(new ProviderStatusResult(ProviderPaymentStatus.NOT_FOUND, null));
    }

    /**
     * El escenario que justifica el gate. {@code charge()} falla, pero el cobro salió: el
     * proveedor movió plata y la respuesta se perdió en el camino.
     */
    @Test
    void lostResponse_throwsButTheChargeWasApplied() {
        UUID paymentId = UUID.randomUUID();

        assertThatThrownBy(() -> charge(paymentId, SandboxScenario.LOST_RESPONSE))
                .isInstanceOf(PaymentProviderTimeoutException.class);

        assertThat(provider.getPaymentStatus(paymentId))
                .as("el cobro ocurrió aunque Relay no se haya enterado")
                .isEqualTo(new ProviderStatusResult(
                        ProviderPaymentStatus.SUCCEEDED, FakePaymentProvider.transactionIdFor(paymentId)));
    }

    /**
     * El proveedor registró el cobro pero él mismo no sabe cómo terminó. Sin
     * transactionId: no llegó a asignarle uno, y es parte de por qué no sabe.
     */
    @Test
    void unknown_returnsServerErrorAndKeepsNotKnowing() {
        UUID paymentId = UUID.randomUUID();

        ChargeResult result = charge(paymentId, SandboxScenario.UNKNOWN);

        assertThat(result.status()).isEqualTo(ChargeStatus.SERVER_ERROR);
        assertThat(result.providerTransactionId()).isNull();

        assertThat(provider.getPaymentStatus(paymentId))
                .isEqualTo(new ProviderStatusResult(ProviderPaymentStatus.UNKNOWN, null));
    }

    // --- la asimetría, que es el punto de todo esto ------------------------------

    /**
     * Los tres escenarios que Relay no puede distinguir, y la única cosa que sí los
     * distingue.
     *
     * <p>TIMEOUT y LOST_RESPONSE lanzan la <b>misma excepción</b>: desde el consumer de
     * charge son el mismo evento, y los dos pagos terminan en UNKNOWN. UNKNOWN llega por
     * otra vía pero aterriza en el mismo estado. Un simulador que solo devolviera enums no
     * podría representar la diferencia, y sin diferencia la reconciliación no tiene nada
     * que descubrir: preguntar sería siempre inútil.
     */
    @Test
    void timeoutAndLostResponse_lookIdenticalToRelayButNotToTheProvider() {
        UUID neverCharged = UUID.randomUUID();
        UUID chargedButLost = UUID.randomUUID();

        Class<PaymentProviderTimeoutException> sameException = PaymentProviderTimeoutException.class;
        assertThatThrownBy(() -> charge(neverCharged, SandboxScenario.TIMEOUT)).isInstanceOf(sameException);
        assertThatThrownBy(() -> charge(chargedButLost, SandboxScenario.LOST_RESPONSE)).isInstanceOf(sameException);

        // Hasta acá los dos pagos son indistinguibles para Relay. Preguntando, no lo son.
        assertThat(provider.getPaymentStatus(neverCharged).status())
                .as("nunca cobrado: es seguro darlo por fallido")
                .isEqualTo(ProviderPaymentStatus.NOT_FOUND);

        assertThat(provider.getPaymentStatus(chargedButLost).status())
                .as("cobrado: darlo por fallido sería perder la plata del merchant")
                .isEqualTo(ProviderPaymentStatus.SUCCEEDED);
    }

    @Test
    void getPaymentStatus_forSomethingNeverCharged_isNotFound() {
        assertThat(provider.getPaymentStatus(UUID.randomUUID()).status())
                .isEqualTo(ProviderPaymentStatus.NOT_FOUND);
        assertThat(provider.getPaymentStatus(null).status())
                .isEqualTo(ProviderPaymentStatus.NOT_FOUND);
    }

    // --- el disparador ----------------------------------------------------------

    @Test
    void aNormalReferenceChargesNormally() {
        UUID paymentId = UUID.randomUUID();

        ChargeResult result = provider.charge(
                new ChargeRequest(paymentId, 1000L, "USD", "orden-4471"));

        assertThat(result.status()).isEqualTo(ChargeStatus.SUCCESS);
    }

    @Test
    void aNullReferenceChargesNormally() {
        ChargeResult result = provider.charge(
                new ChargeRequest(UUID.randomUUID(), 1000L, "USD", null));

        assertThat(result.status()).isEqualTo(ChargeStatus.SUCCESS);
    }

    /** El merchant conserva su propia correlación después de los dos puntos. */
    @Test
    void theMerchantSuffixIsIgnored() {
        ChargeResult result = provider.charge(
                new ChargeRequest(UUID.randomUUID(), 1000L, "USD", "RELAY_TEST_FAILED:orden-4471"));

        assertThat(result.status()).isEqualTo(ChargeStatus.DECLINED);
    }

    @Test
    void theScenarioIsCaseInsensitive() {
        ChargeResult result = provider.charge(
                new ChargeRequest(UUID.randomUUID(), 1000L, "USD", "relay_test_failed"));

        assertThat(result.status()).isEqualTo(ChargeStatus.DECLINED);
    }

    /**
     * Un escenario mal escrito cobra normalmente en vez de romper. Es un error de quien
     * integra, y fallar el cobro convertiría un typo en un pago roto.
     */
    @Test
    void anUnrecognisedScenarioChargesNormally() {
        ChargeResult result = provider.charge(
                new ChargeRequest(UUID.randomUUID(), 1000L, "USD", "RELAY_TEST_DECLINE"));

        assertThat(result.status()).isEqualTo(ChargeStatus.SUCCESS);
    }

    /**
     * Con el sandbox apagado, una referencia mágica es una etiqueta cualquiera. Es el
     * default, y es lo que impide que un merchant elija el desenlace de su propio pago.
     */
    @Test
    void withSandboxDisabled_magicReferencesAreJustLabels() {
        FakePaymentProvider production = new FakePaymentProvider(new SandboxProperties(false));
        UUID paymentId = UUID.randomUUID();

        ChargeResult result = production.charge(
                new ChargeRequest(paymentId, 1000L, "USD", "RELAY_TEST_LOST_RESPONSE"));

        assertThat(result.status()).isEqualTo(ChargeStatus.SUCCESS);
        assertThat(production.getPaymentStatus(paymentId).status())
                .isEqualTo(ProviderPaymentStatus.SUCCEEDED);
    }

    @Test
    void withSandboxDisabled_noScenarioCanMakeTheChargeThrow() {
        FakePaymentProvider production = new FakePaymentProvider(new SandboxProperties(false));

        for (SandboxScenario scenario : SandboxScenario.values()) {
            assertThatCode(() -> production.charge(new ChargeRequest(
                    UUID.randomUUID(), 1000L, "USD", SandboxScenario.PREFIX + scenario.name())))
                    .as("escenario %s", scenario)
                    .doesNotThrowAnyException();
        }
    }

    // --- el resto ---------------------------------------------------------------

    @Test
    void theTransactionIdIsStableForAPayment() {
        UUID paymentId = UUID.randomUUID();

        String first = charge(paymentId, SandboxScenario.SUCCESS).providerTransactionId();
        String second = charge(paymentId, SandboxScenario.SUCCESS).providerTransactionId();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void chargeCountCountsEveryCall() {
        provider.resetChargeCount();

        charge(UUID.randomUUID(), SandboxScenario.SUCCESS);
        assertThatThrownBy(() -> charge(UUID.randomUUID(), SandboxScenario.TIMEOUT))
                .isInstanceOf(PaymentProviderTimeoutException.class);

        assertThat(provider.chargeCount())
                .as("el cobro que fallo tambien fue un cobro")
                .isEqualTo(2);
    }

    /**
     * El registro de cobros no crece para siempre. Vive lo que vive el proceso, y un
     * sandbox de larga vida lo llenaría; al pasar el techo se van los más viejos.
     */
    @Test
    void theChargeLogIsBounded() {
        UUID oldest = UUID.randomUUID();
        charge(oldest, SandboxScenario.SUCCESS);
        assertThat(provider.getPaymentStatus(oldest).status()).isEqualTo(ProviderPaymentStatus.SUCCEEDED);

        for (int i = 0; i < 10_000; i++) {
            charge(UUID.randomUUID(), SandboxScenario.SUCCESS);
        }

        assertThat(provider.getPaymentStatus(oldest).status())
                .as("el cobro mas viejo se descarto al pasar el techo")
                .isEqualTo(ProviderPaymentStatus.NOT_FOUND);
    }

    private ChargeResult charge(UUID paymentId, SandboxScenario scenario) {
        return provider.charge(new ChargeRequest(
                paymentId, 1000L, "USD", SandboxScenario.PREFIX + scenario.name()));
    }
}
