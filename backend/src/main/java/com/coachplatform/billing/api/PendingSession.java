package com.coachplatform.billing.api;

import java.time.Instant;
import java.util.UUID;

/** An attendance (one student's place in an event) that already started but has not been marked attended / no-show yet, with the modality of its event. */
public record PendingSession(UUID attendanceId, UUID sessionId, UUID studentId, Instant startsAt, Instant endsAt, Modality eventModality) {
}
