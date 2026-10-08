package com.coachplatform.billing;

import com.coachplatform.billing.api.CycleStatus;
import com.coachplatform.billing.domain.CycleCalendar;
import com.coachplatform.billing.domain.CycleState;
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
import java.time.LocalDate;
import java.util.UUID;

/** Persistence shell of a cycle. The rules live in {@code billing.domain}; this class only converts to/from {@link CycleState}. */
@Entity
@Table(name = "cycle")
class Cycle extends TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "original_end_date", nullable = false, updatable = false)
    private LocalDate originalEndDate;

    @Column(name = "classes_included", nullable = false, updatable = false)
    private int classesIncluded;

    @Column(name = "classes_used", nullable = false)
    private int classesUsed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CycleStatus status;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Cycle() {
    }

    Cycle(UUID studentId, UUID planId, CycleState state) {
        this.studentId = studentId;
        this.planId = planId;
        this.startDate = state.startDate();
        this.originalEndDate = state.originalEndDate();
        this.classesIncluded = state.classesIncluded();
        apply(state, null);
    }

    UUID getId() { return id; }
    UUID getStudentId() { return studentId; }
    UUID getPlanId() { return planId; }
    LocalDate getOriginalEndDate() { return originalEndDate; }
    Instant getCreatedAt() { return createdAt; }

    CycleState toState(CycleCalendar calendar) {
        LocalDate completedOn = status == CycleStatus.COMPLETED && closedAt != null ? calendar.toLocalDate(closedAt) : null;
        return new CycleState(startDate, endDate, originalEndDate, classesIncluded, classesUsed, status, completedOn);
    }

    /** Copies a new domain state into the entity, stamping closed_at the first time the cycle stops being ACTIVE. */
    void apply(CycleState state, Instant now) {
        this.endDate = state.endDate();
        this.classesUsed = state.classesUsed();
        this.status = state.status();
        if (state.isActive()) {
            this.closedAt = null;
        } else if (this.closedAt == null) {
            this.closedAt = now;
        }
    }
}
