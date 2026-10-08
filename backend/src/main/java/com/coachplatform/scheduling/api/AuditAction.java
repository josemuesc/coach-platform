package com.coachplatform.scheduling.api;

/** What changed in an attendance, as recorded in attendance_audit. */
public enum AuditAction {
    BOOK, RESCHEDULE, MARK, CANCEL, CONFIRM, TRANSFER
}
