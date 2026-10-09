package com.coachplatform.billing.api;

public enum PaymentMethod {
    NEQUI,
    TRANSFER,
    CASH,
    /** Anything else (a card at the gym, a deposit...): the reference can say what. */
    OTHER
}
