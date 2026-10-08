package com.coachplatform.scheduling.domain;

/** A scheduling business rule was violated. The service layer maps {@link Code} to an HTTP error. */
public class SchedulingRuleException extends RuntimeException {

    public enum Code {
        NO_ACTIVE_CYCLE,
        CLASS_IN_PAST,
        TOO_SOON,
        OUTSIDE_CYCLE,
        NOT_AVAILABLE,
        BLOCKED,
        SLOT_TAKEN,
        QUOTA_EXCEEDED,
        CANCELLATION_WINDOW_CLOSED,
        CLASS_ALREADY_STARTED,
        CLASS_NOT_STARTED,
        INVALID_STATE,
        ALREADY_MARKED,
        CYCLE_CLOSED,
        REASON_REQUIRED
    }

    private final Code code;

    public SchedulingRuleException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
