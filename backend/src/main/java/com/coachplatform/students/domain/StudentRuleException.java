package com.coachplatform.students.domain;

/** A rule about the student's profile or consents was violated. The service layer maps {@link Code} to an HTTP error. */
public class StudentRuleException extends RuntimeException {

    public enum Code {
        INVALID_BIRTH_DATE,
        GUARDIAN_REQUIRED,
        GUARDIAN_INCOMPLETE,
        AUDIENCE_CHANGE_BLOCKED,
        DATA_CONSENT_REQUIRED,
        CONSENT_VERSION_MISMATCH,
        GUARDIAN_CONSENT_NOT_ALLOWED,
        CONSENT_ALREADY_ACTIVE,
        CONSENT_NOT_ACTIVE
    }

    private final Code code;

    public StudentRuleException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
