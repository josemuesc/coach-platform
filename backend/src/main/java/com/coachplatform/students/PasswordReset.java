package com.coachplatform.students;

import com.coachplatform.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A one-time password reset link the coach generated for a student's login. Only the SHA-256 of the token is stored. */
@Entity
@Table(name = "password_reset")
class PasswordReset extends TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    /** The login that will get the new password (the guardian's account for a minor). */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    private UUID createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected PasswordReset() {
    }

    PasswordReset(UUID studentId, UUID userId, String tokenHash, UUID createdByUserId, Instant createdAt, Instant expiresAt) {
        this.studentId = studentId;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdByUserId = createdByUserId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    UUID getId() { return id; }
    UUID getStudentId() { return studentId; }
    UUID getUserId() { return userId; }
    UUID getCreatedByUserId() { return createdByUserId; }
    Instant getCreatedAt() { return createdAt; }
    Instant getExpiresAt() { return expiresAt; }
    Instant getUsedAt() { return usedAt; }
    Instant getRevokedAt() { return revokedAt; }

    void revoke(Instant now) { this.revokedAt = now; }
}
