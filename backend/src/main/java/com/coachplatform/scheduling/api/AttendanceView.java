package com.coachplatform.scheduling.api;

import com.coachplatform.billing.api.Modality;
import java.time.Instant;
import java.util.UUID;

/**
 * One student's place in an event. studentId / studentName are filled for the COACH and are null in the student's own views,
 * which only carry the event's modality, capacity and how many places are taken - never who the others are.
 */
public record AttendanceView(UUID id, UUID eventId, UUID studentId, String studentName, UUID cycleId, Instant startsAt,
                             Instant endsAt, Modality modality, int capacity, int occupied, AttendanceStatus status,
                             UUID rescheduledFrom, String cancelReason, boolean override, String overrideReason) {
}
