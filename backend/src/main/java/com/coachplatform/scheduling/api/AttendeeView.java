package com.coachplatform.scheduling.api;

import java.util.UUID;

/** One student's live place in an event, as the COACH sees it. Overrides are visible here (who put the student, and why). */
public record AttendeeView(UUID attendanceId, UUID studentId, String studentName, AttendanceStatus status,
                           boolean override, String overrideReason) {
}
