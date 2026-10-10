package com.coachplatform.billing.api;

/** Why a payment cannot be registered right now. */
public enum PaymentBlockedBy {
    /** The student still has an active cycle that has not reached its last day. */
    ACTIVE_CYCLE,
    /** The cycle reached its last day but classes that already started are still unmarked. */
    PENDING_SESSIONS
}
