package com.movieticket.saga;

public enum SagaStatus {
    /** A request is actively executing steps; also what the recovery job looks for after a crash. */
    IN_PROGRESS,
    /** Seat is locked; waiting for POST /api/payments (or lock expiry). */
    AWAITING_PAYMENT,
    COMPLETED,
    /** A step failed and the compensating actions have run. */
    COMPENSATED,
    /** Compensation itself could not finish; needs manual attention. */
    FAILED
}
