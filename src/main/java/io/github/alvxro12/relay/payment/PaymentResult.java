package io.github.alvxro12.relay.payment;

public record PaymentResult(Payment payment, Outcome outcome) {

    public enum Outcome { CREATED, REPLAY, CONFLICT }

    public static PaymentResult created(Payment p) { return new PaymentResult(p, Outcome.CREATED); }
    public static PaymentResult replay(Payment p)  { return new PaymentResult(p, Outcome.REPLAY); }
    public static PaymentResult conflict(Payment p) { return new PaymentResult(p, Outcome.CONFLICT); }
}