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
        REASON_REQUIRED,
        MODALITY_MISMATCH,
        EVENT_FULL,
        ALREADY_BOOKED,
        EVENT_ALREADY_STARTED,
        CAPACITY_NOT_CONFIGURABLE,
        INVALID_CAPACITY,
        CAPACITY_BELOW_OCCUPANCY,
        INVALID_QR,
        CONFIRMATION_WINDOW_CLOSED,
        QR_NOT_OPEN_YET,
        QR_WINDOW_CLOSED,
        INVALID_START_TIME,
        INVALID_BLOCK,
        SHARED_LIMIT_EXCEEDED
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
