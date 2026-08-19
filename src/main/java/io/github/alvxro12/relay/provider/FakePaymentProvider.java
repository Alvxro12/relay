package io.github.alvxro12.relay.provider;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class FakePaymentProvider implements PaymentProvider {

    private volatile ChargeStatus forcedStatus = ChargeStatus.SUCCESS;
    private volatile String forcedTransactionId;

    // Seam de test: se ejecuta dentro del cobro, con el PROCESSING ya commiteado
    // y ninguna transacción abierta. Sirve para frenar el cobro en vuelo y mirar
    // desde afuera qué ve el resto del sistema mientras tanto. Es de un solo uso.
    private volatile Runnable duringNextCharge;

    private final AtomicInteger chargeCount = new AtomicInteger();

    public void forceNextResult(ChargeStatus status) {
        this.forcedStatus = status;
    }

    /** Fija el id de transacción que devolverá el próximo cobro exitoso; null vuelve al id generado. */
    public void forceNextTransactionId(String providerTransactionId) {
        this.forcedTransactionId = providerTransactionId;
    }

    public void onNextCharge(Runnable hook) {
        this.duringNextCharge = hook;
    }

    /**
     * Cuenta cuántas veces se llamó al proveedor. Es lo que distingue "el pago
     * quedó en el estado correcto" de "se cobró una sola vez": el estado final
     * se ve igual si el cobro salió una vez o tres.
     */
    public int chargeCount() {
        return chargeCount.get();
    }

    public void resetChargeCount() {
        chargeCount.set(0);
    }

    @Override
    public ChargeResult charge(ChargeRequest request) {
        chargeCount.incrementAndGet();

        Runnable hook = this.duringNextCharge;
        this.duringNextCharge = null;
        if (hook != null) {
            hook.run();
        }

        if (forcedStatus == ChargeStatus.TIMEOUT) {
            throw new PaymentProviderTimeoutException("Payment provider timed out");
        }

        if (forcedStatus == ChargeStatus.SERVER_ERROR) {
            return new ChargeResult(forcedStatus, null);
        }

        // SUCCESS, ACCEPTED y DECLINED devuelven id de transacción. En ACCEPTED es lo
        // que hace útil al estado: sin ese id no hay con qué correlacionar el webhook
        // que después va a resolver el pago.

        String transactionId = forcedTransactionId != null
                ? forcedTransactionId
                : "fake_txn_" + UUID.randomUUID();
        return new ChargeResult(forcedStatus, transactionId);
    }
}
