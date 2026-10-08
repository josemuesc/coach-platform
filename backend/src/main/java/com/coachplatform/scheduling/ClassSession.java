package com.coachplatform.scheduling;

import com.coachplatform.scheduling.api.SessionStatus;
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

/** Persistence shell of a class. The rules live in {@code scheduling.domain}. ends_at is fixed when it is booked. */
@Entity
@Table(name = "class_session")
class ClassSession extends TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    /** Updatable: on a renewal the classes that have not started yet move to the new cycle. */
    @Column(name = "cycle_id", nullable = false)
    private UUID cycleId;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false, updatable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status = SessionStatus.SCHEDULED;

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

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ClassSession() {
    }

    ClassSession(UUID studentId, UUID cycleId, Instant startsAt, Instant endsAt, UUID rescheduledFrom, UUID createdBy) {
        this.studentId = studentId;
        this.cycleId = cycleId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.rescheduledFrom = rescheduledFrom;
        this.createdBy = createdBy;
    }

    UUID getId() { return id; }
    UUID getStudentId() { return studentId; }
    UUID getCycleId() { return cycleId; }
    Instant getStartsAt() { return startsAt; }
    Instant getEndsAt() { return endsAt; }
    SessionStatus getStatus() { return status; }
    UUID getRescheduledFrom() { return rescheduledFrom; }
    String getCancelReason() { return cancelReason; }

    void cancel(SessionStatus newStatus, UUID by, String reason, Instant now) {
        this.status = newStatus;
        this.cancelledBy = by;
        this.cancelledAt = now;
        this.cancelReason = reason;
    }

    void mark(SessionStatus newStatus, UUID by, Instant now) {
        this.status = newStatus;
        this.markedBy = by;
        this.markedAt = now;
    }

    void moveToCycle(UUID newCycleId) {
        this.cycleId = newCycleId;
    }
}
