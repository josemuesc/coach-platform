package com.coachplatform.scheduling;

import com.coachplatform.scheduling.api.AttendanceStatus;
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

/**
 * One student's place in an event. Cycle consumption, the cancellation window and marking attendance are all decided
 * per attendance, never per event.
 */
@Entity
@Table(name = "session_attendance")
class SessionAttendance extends TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    /** Updatable: on a renewal the places whose event has not started yet move to the new cycle. */
    @Column(name = "cycle_id", nullable = false)
    private UUID cycleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttendanceStatus status = AttendanceStatus.SCHEDULED;

    @Column(name = "rescheduled_from", updatable = false)
    private UUID rescheduledFrom;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "marked_by")
    private UUID markedBy;

    @Column(name = "marked_at")
    private Instant markedAt;

    @Column(nullable = false)
    private boolean override;

    @Column(name = "override_reason")
    private String overrideReason;

    @Column(name = "override_by")
    private UUID overrideBy;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected SessionAttendance() {
    }

    SessionAttendance(UUID sessionId, UUID studentId, UUID cycleId, UUID rescheduledFrom, UUID createdBy) {
        this.sessionId = sessionId;
        this.studentId = studentId;
        this.cycleId = cycleId;
        this.rescheduledFrom = rescheduledFrom;
        this.createdBy = createdBy;
    }

    UUID getId() { return id; }
    UUID getSessionId() { return sessionId; }
    UUID getStudentId() { return studentId; }
    UUID getCycleId() { return cycleId; }
    AttendanceStatus getStatus() { return status; }
    UUID getRescheduledFrom() { return rescheduledFrom; }
    String getCancelReason() { return cancelReason; }
    boolean isOverride() { return override; }
    String getOverrideReason() { return overrideReason; }

    void cancel(AttendanceStatus newStatus, UUID by, String reason, Instant now) {
        this.status = newStatus;
        this.cancelledBy = by;
        this.cancelledAt = now;
        this.cancelReason = reason;
    }

    void mark(AttendanceStatus newStatus, UUID by, Instant now) {
        this.status = newStatus;
        this.markedBy = by;
        this.markedAt = now;
    }

    void moveToCycle(UUID newCycleId) {
        this.cycleId = newCycleId;
    }

    /** The coach put the student here against the modality / capacity rules: who and why stay on record. */
    void markOverride(UUID by, String reason) {
        this.override = true;
        this.overrideBy = by;
        this.overrideReason = reason;
    }
}
