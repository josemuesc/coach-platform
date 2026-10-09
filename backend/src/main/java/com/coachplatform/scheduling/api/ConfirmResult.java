package com.coachplatform.scheduling.api;

/** alreadyConfirmed: the class was confirmed before; nothing changed. */
public record ConfirmResult(AttendanceView attendance, boolean alreadyConfirmed) {
}
