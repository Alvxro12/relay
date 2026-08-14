package io.github.alvxro12.relay.provider;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FakePaymentProvider implements PaymentProvider {

    private ChargeStatus forcedStatus = ChargeStatus.SUCCESS;

    public void forceNextResult(ChargeStatus status) {
        this.forcedStatus = status;
    }

    @Override
    public ChargeResult charge(ChargeRequest request) {
        if (forcedStatus == ChargeStatus.TIMEOUT) {
            throw new PaymentProviderTimeoutException("Payment provider timed out");
        }

        if (forcedStatus == ChargeStatus.SERVER_ERROR) {
            return new ChargeResult(forcedStatus, null);
        }

        return new ChargeResult(forcedStatus, "fake_txn_" + UUID.randomUUID());
    }
}
