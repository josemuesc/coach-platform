package com.coachplatform.billing.api;

import java.time.Instant;
import java.util.UUID;

/** A booked attendance whose event has not started yet, with the modality of that event. */
public record FutureAttendance(UUID attendanceId, UUID sessionId, UUID studentId, Instant startsAt, Instant endsAt,
                               Modality eventModality) {
}
