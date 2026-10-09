package com.coachplatform.scheduling.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One student's live place in an event, as the COACH sees it. Overrides are visible here (who put the student, and why), and
 * so is whether the student confirmed the class ({@code onlyMarkedByCoach}: marked by the coach, never confirmed).
 */
public record AttendeeView(UUID attendanceId, UUID studentId, String studentName, AttendanceStatus status,
                           boolean override, String overrideReason, boolean studentConfirmed,
                           ConfirmationMethod confirmationMethod, Instant confirmedAt, boolean onlyMarkedByCoach) {
}
