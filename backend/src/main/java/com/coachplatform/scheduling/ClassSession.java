package com.coachplatform.scheduling;

import com.coachplatform.billing.api.Modality;
import com.coachplatform.scheduling.api.EventStatus;
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
 * The coach's EVENT: a time slot that one student (personalized) or several (semi-personalized) share. Its end,
 * modality and capacity are fixed when it is created; only the capacity of a semi event can be changed by the coach.
 */
@Entity
@Table(name = "class_session")
class ClassSession extends TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false, updatable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Modality modality;

    @Column(nullable = false)
    private int capacity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status = EventStatus.SCHEDULED;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ClassSession() {
    }

    ClassSession(Instant startsAt, Instant endsAt, Modality modality, int capacity, UUID createdBy) {
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.modality = modality;
        this.capacity = capacity;
        this.createdBy = createdBy;
    }

    UUID getId() { return id; }
    Instant getStartsAt() { return startsAt; }
    Instant getEndsAt() { return endsAt; }
    Modality getModality() { return modality; }
    int getCapacity() { return capacity; }
    EventStatus getStatus() { return status; }

    void setCapacity(int capacity) { this.capacity = capacity; }

    void cancel(UUID by, String reason, Instant now) {
        this.status = EventStatus.CANCELLED;
        this.cancelledBy = by;
        this.cancelledAt = now;
        this.cancelReason = reason;
    }
}
