package com.coachplatform.scheduling.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.coachplatform.billing.api.Modality;
import java.time.Instant;
import java.util.UUID;

/**
 * One student's place in an event. studentId / studentName are filled for the COACH and are null in the student's own views,
 * which only carry the event's modality, capacity and how many places are taken - never who the others are.
 * studentConfirmed / confirmationMethod / confirmedAt: the student confirmed the class (QR in person, or later). A mark by the
 * coach with no confirmation is flagged {@code onlyMarkedByCoach}.
 */
public record AttendanceView(UUID id, UUID eventId, @Schema(nullable = true) UUID studentId, @Schema(nullable = true) String studentName, UUID cycleId, Instant startsAt,
                             Instant endsAt, Modality modality, int capacity, int occupied, AttendanceStatus status,
                             @Schema(nullable = true) UUID rescheduledFrom, @Schema(nullable = true) String cancelReason, boolean override, @Schema(nullable = true) String overrideReason,
                             boolean studentConfirmed, @Schema(nullable = true) ConfirmationMethod confirmationMethod, @Schema(nullable = true) Instant confirmedAt,
                             boolean onlyMarkedByCoach) {
}
