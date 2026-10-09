package com.coachplatform.auth;

import com.coachplatform.auth.api.AccountEvent;
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
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Who touched whose login and when. Append-only. Holds no token, no password and no IP. */
@Entity
@Immutable
@Table(name = "account_audit")
class AccountAudit extends TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AccountEvent event;

    @Column(name = "target_user_id", nullable = false, updatable = false)
    private UUID targetUserId;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private UUID actorUserId;

    @Column(name = "student_id", updatable = false)
    private UUID studentId;

    @Column(name = "reset_id", updatable = false)
    private UUID resetId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AccountAudit() {
    }

    AccountAudit(AccountEvent event, UUID targetUserId, UUID actorUserId, UUID studentId, UUID resetId, Instant occurredAt) {
        this.event = event;
        this.targetUserId = targetUserId;
        this.actorUserId = actorUserId;
        this.studentId = studentId;
        this.resetId = resetId;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt comes from the Clock");
    }

    UUID getId() { return id; }
    AccountEvent getEvent() { return event; }
    UUID getTargetUserId() { return targetUserId; }
    UUID getActorUserId() { return actorUserId; }
    UUID getStudentId() { return studentId; }
    UUID getResetId() { return resetId; }
    Instant getOccurredAt() { return occurredAt; }
}
