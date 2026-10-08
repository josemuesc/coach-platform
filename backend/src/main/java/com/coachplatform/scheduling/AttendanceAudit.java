package com.coachplatform.scheduling;

import com.coachplatform.scheduling.api.ActorRole;
import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.api.AuditAction;
import com.coachplatform.scheduling.api.AuditMethod;
import com.coachplatform.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Who did what to an attendance, how and when. Append-only: never edited, never deleted. */
@Entity
@Immutable
@Table(name = "attendance_audit")
class AttendanceAudit extends TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "attendance_id", nullable = false, updatable = false)
    private UUID attendanceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", updatable = false)
    private AttendanceStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, updatable = false)
    private AttendanceStatus newStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AuditMethod method;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private UUID actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", nullable = false, updatable = false)
    private ActorRole actorRole;

    @Column(updatable = false)
    private String reason;

    /** Always passed from the injected Clock by the service; the column DEFAULT now() is only a safety net. */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AttendanceAudit() {
    }

    AttendanceAudit(UUID attendanceId, AuditAction action, AttendanceStatus previousStatus, AttendanceStatus newStatus,
                    AuditMethod method, UUID actorUserId, ActorRole actorRole, String reason, Instant occurredAt) {
        this.attendanceId = attendanceId;
        this.action = action;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.method = method;
        this.actorUserId = actorUserId;
        this.actorRole = actorRole;
        this.reason = reason;
        this.occurredAt = java.util.Objects.requireNonNull(occurredAt, "occurredAt comes from the Clock");
    }

    UUID getId() { return id; }
    UUID getAttendanceId() { return attendanceId; }
    AuditAction getAction() { return action; }
    AttendanceStatus getPreviousStatus() { return previousStatus; }
    AttendanceStatus getNewStatus() { return newStatus; }
    AuditMethod getMethod() { return method; }
    ActorRole getActorRole() { return actorRole; }
    String getReason() { return reason; }
    Instant getOccurredAt() { return occurredAt; }
}
