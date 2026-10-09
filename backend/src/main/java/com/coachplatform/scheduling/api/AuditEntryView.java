package com.coachplatform.scheduling.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** One immutable line of the history of an attendance: who did what, how, and when. */
public record AuditEntryView(AuditAction action, @Schema(nullable = true) AttendanceStatus previousStatus, AttendanceStatus newStatus, AuditMethod method,
                             ActorRole actorRole, UUID actorUserId, @Schema(nullable = true) String reason, Instant occurredAt) {
}
