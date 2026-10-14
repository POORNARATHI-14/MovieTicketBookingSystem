package com.movieticket.payment;

/** Outcome of asking the (simulated) gateway to charge or refund. */
public record GatewayResult(Outcome outcome, String reference, String message) {

    public enum Outcome {
        SUCCESS,
        DECLINED,
        TIMEOUT
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }
}
