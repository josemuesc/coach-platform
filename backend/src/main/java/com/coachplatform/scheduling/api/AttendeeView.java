package com.coachplatform.scheduling.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * One student's live place in an event, as the COACH sees it. Overrides are visible here (who put the student, and why), and
 * so is whether the student confirmed the class ({@code onlyMarkedByCoach}: marked by the coach, never confirmed).
 * {@code canMark}: the server would accept a mark (or a switch of the result) right now. {@code minor}: the student is under 18.
 */
public record AttendeeView(UUID attendanceId, UUID studentId, String studentName, AttendanceStatus status,
                           boolean override, @Schema(nullable = true) String overrideReason, boolean studentConfirmed,
                           @Schema(nullable = true) ConfirmationMethod confirmationMethod, @Schema(nullable = true) Instant confirmedAt, boolean onlyMarkedByCoach,
                           boolean canMark, boolean minor) {
}
