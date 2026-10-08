package com.coachplatform.students;

import com.coachplatform.students.api.ConsentType;
import com.coachplatform.students.domain.ConsentGrant;
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

/** One authorization accepted: type, version and the SHA-256 of the exact text shown. Immutable; no IP is kept. */
@Entity
@Immutable
@Table(name = "consent_record")
class ConsentRecord extends TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "student_id", nullable = false, updatable = false)
    private UUID studentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ConsentType type;

    @Column(nullable = false, updatable = false)
    private String version;

    @Column(name = "text_sha256", nullable = false, updatable = false)
    private String textSha256;

    /** Only for DATA_GUARDIAN: the guardian on file when it was accepted (database CHECK: set exactly for that type). */
    @Column(name = "signer_name", updatable = false)
    private String signerName;

    @Column(name = "signer_relationship", updatable = false)
    private String signerRelationship;

    /** Always passed from the injected Clock by the service; the column DEFAULT now() is only a safety net. */
    @Column(name = "accepted_at", nullable = false, updatable = false)
    private Instant acceptedAt;

    @Column(name = "accepted_by_user_id", nullable = false, updatable = false)
    private UUID acceptedByUserId;

    protected ConsentRecord() {
    }

    ConsentRecord(UUID studentId, ConsentGrant grant, Instant acceptedAt, UUID acceptedByUserId) {
        this.studentId = Objects.requireNonNull(studentId);
        this.type = grant.type();
        this.version = grant.version();
        this.textSha256 = grant.textSha256();
        this.signerName = grant.signerName();
        this.signerRelationship = grant.signerRelationship();
        this.acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt comes from the Clock");
        this.acceptedByUserId = Objects.requireNonNull(acceptedByUserId);
    }

    UUID getId() { return id; }
    UUID getStudentId() { return studentId; }
    ConsentType getType() { return type; }
    String getVersion() { return version; }
    String getTextSha256() { return textSha256; }
    String getSignerName() { return signerName; }
    String getSignerRelationship() { return signerRelationship; }
    Instant getAcceptedAt() { return acceptedAt; }
}
