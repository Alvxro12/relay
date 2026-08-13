package io.github.alvxro12.relay.provider;

public class PaymentProviderTimeoutException extends RuntimeException {

    public PaymentProviderTimeoutException(String message) {
        super(message);
    }
}
