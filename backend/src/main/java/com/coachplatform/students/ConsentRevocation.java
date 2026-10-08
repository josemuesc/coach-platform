package com.coachplatform.students;

import com.coachplatform.students.api.ConsentType;
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

/** One authorization revoked. Immutable. The current state of a consent is the latest acceptance or revocation. */
@Entity
@Immutable
@Table(name = "consent_revocation")
class ConsentRevocation extends TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ConsentType type;

    /** Always passed from the injected Clock by the service; the column DEFAULT now() is only a safety net. */
    @Column(name = "revoked_at", nullable = false, updatable = false)
    private Instant revokedAt;

    @Column(name = "revoked_by_user_id", nullable = false, updatable = false)
    private UUID revokedByUserId;

    protected ConsentRevocation() {
    }

    ConsentRevocation(UUID studentId, ConsentType type, Instant revokedAt, UUID revokedByUserId) {
        this.studentId = Objects.requireNonNull(studentId);
        this.type = Objects.requireNonNull(type);
        this.revokedAt = Objects.requireNonNull(revokedAt, "revokedAt comes from the Clock");
        this.revokedByUserId = Objects.requireNonNull(revokedByUserId);
    }

    UUID getId() { return id; }
    UUID getStudentId() { return studentId; }
    ConsentType getType() { return type; }
    Instant getRevokedAt() { return revokedAt; }
}
