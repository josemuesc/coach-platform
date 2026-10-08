package com.coachplatform.billing.api;

import java.time.Instant;
import java.util.UUID;

/** A class that already started but has not been marked attended / no-show yet. */
public record PendingSession(UUID sessionId, UUID studentId, Instant startsAt, Instant endsAt) {
}
