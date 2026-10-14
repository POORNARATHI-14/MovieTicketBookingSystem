package com.movieticket.payment;

/** Simulator "test cards". There is no real payment gateway behind any of them. */
public enum PaymentMethod {
    /** Gateway approves the charge. */
    TEST_SUCCESS,
    /** Gateway declines the charge (insufficient funds). */
    TEST_DECLINED,
    /** Gateway does not answer in time; nothing is charged. */
    TEST_TIMEOUT,
    /** Charge is captured, then a LATER Saga step fails - demonstrates the refund compensation. */
    TEST_CAPTURE_THEN_FAIL,
    /** Approves with the probability configured in app.payment.simulator.success-rate. */
    TEST_RANDOM
}
