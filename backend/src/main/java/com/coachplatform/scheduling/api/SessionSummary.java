package com.coachplatform.scheduling.api;

import java.time.Instant;
import java.util.UUID;

/** studentName is filled for the coach and left null in the student's own views. */
public record SessionSummary(UUID id, UUID studentId, String studentName, UUID cycleId, Instant startsAt, Instant endsAt,
                             SessionStatus status, UUID rescheduledFrom, String cancelReason) {
}
