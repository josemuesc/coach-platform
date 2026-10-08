package com.coachplatform.billing.domain;

/** A business rule of the cycle lifecycle was violated. The service layer maps {@link Code} to an HTTP error. */
public class CycleRuleException extends RuntimeException {

    public enum Code {
        ACTIVE_CYCLE_EXISTS,
        INVALID_PAYMENT_DATE,
        CYCLE_NOT_ACTIVE,
        INVALID_EXTENSION,
        EXTENSION_LIMIT_EXCEEDED,
        INVALID_PLAN,
        PENDING_SESSIONS_TO_MARK,
        TRANSFER_EXCEEDS_PLAN,
        REOPEN_NOT_ALLOWED,
        OVERRIDE_REASON_REQUIRED
    }

    private final Code code;

    public CycleRuleException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
