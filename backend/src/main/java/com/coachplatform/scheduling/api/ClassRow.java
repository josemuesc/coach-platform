package com.coachplatform.scheduling.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * One class of a cycle, in the shape of the coach's sheet. {@code number} counts the live classes (booked, attended, no-show) of the
 * cycle in date order; cancelled and rescheduled places carry no number. canConfirm: the student may still confirm it from their history.
 */
public record ClassRow(@Schema(nullable = true) Integer number, UUID attendanceId, Instant startsAt, String date, String time, AttendanceStatus status,
                       boolean studentConfirmed, @Schema(nullable = true) ConfirmationMethod confirmationMethod, boolean onlyMarkedByCoach,
                       boolean override, boolean canConfirm, @Schema(nullable = true) String cancelReason) {
}
