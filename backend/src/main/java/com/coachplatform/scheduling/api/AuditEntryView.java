package com.coachplatform.scheduling.api;

import java.time.Instant;
import java.util.UUID;

/** One immutable line of the history of an attendance: who did what, how, and when. */
public record AuditEntryView(AuditAction action, AttendanceStatus previousStatus, AttendanceStatus newStatus, AuditMethod method,
                             ActorRole actorRole, UUID actorUserId, String reason, Instant occurredAt) {
}
