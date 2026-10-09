package com.coachplatform.scheduling.api;

/**
 * markedAttended: this scan marked the class ATTENDED (and used one class of the cycle). alreadyConfirmed: the student had
 * already confirmed this class; nothing changed.
 */
public record ConfirmQrResult(AttendanceView attendance, boolean markedAttended, boolean alreadyConfirmed) {
}
