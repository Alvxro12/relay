package io.github.alvxro12.relay.provider;

import java.util.UUID;

public interface PaymentProvider {

    ChargeResult charge(ChargeRequest request);

    /**
     * Pregunta que paso con un cobro.
     *
     * <p>La clave es el {@code paymentId} de Relay y no el {@code providerTransactionId},
     * que seria lo intuitivo. Motivo: cuando {@code charge()} corta por timeout no hay
     * {@code providerTransactionId} —la llamada nunca retorno— y esos son exactamente los
     * pagos que hay que poder resolver. Una consulta que solo acepta el id del proveedor
     * es inconsultable justo cuando hace falta.
     *
     * <p>Por eso {@link ChargeRequest} lleva el {@code paymentId}: es la clave de
     * idempotencia que Relay le da al proveedor al cobrar, y con la que despues pregunta.
     */
    ProviderStatusResult getPaymentStatus(UUID paymentId);
}
