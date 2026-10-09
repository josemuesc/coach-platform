package com.coachplatform.students;

import com.coachplatform.students.api.ConsentType;
import com.coachplatform.students.domain.GuardianData;
import com.coachplatform.tenant.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "student")
class Student extends TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String email;

    @Column(name = "whatsapp_phone")
    private String whatsappPhone;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private boolean active = true;

    private String goal;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "guardian_name")
    private String guardianName;

    @Column(name = "guardian_relationship")
    private String guardianRelationship;

    @Column(name = "guardian_phone")
    private String guardianPhone;

    @Column(name = "guardian_email")
    private String guardianEmail;

    /** Reflect the CURRENT consents (the source of truth is consent_record / consent_revocation). */
    @Column(name = "data_consent_at")
    private Instant dataConsentAt;

    @Column(name = "whatsapp_opt_in_at")
    private Instant whatsappOptInAt;

    /** Set when a data consent was revoked: the account is suspended and waits for anonymization. Never cleared here. */
    @Column(name = "anonymization_requested_at")
    private Instant anonymizationRequestedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Student() {
    }

    Student(String fullName, String email, String whatsappPhone) {
        this.fullName = fullName;
        this.email = email;
        this.whatsappPhone = whatsappPhone;
    }

    UUID getId() { return id; }
    String getFullName() { return fullName; }
    String getEmail() { return email; }
    String getWhatsappPhone() { return whatsappPhone; }
    UUID getUserId() { return userId; }
    boolean isActive() { return active; }
    boolean hasAccount() { return userId != null; }
    boolean isMarkedForAnonymization() { return anonymizationRequestedAt != null; }
    Instant getWhatsappOptInAt() { return whatsappOptInAt; }
    Instant getDataConsentAt() { return dataConsentAt; }
    String getGoal() { return goal; }
    LocalDate getBirthDate() { return birthDate; }

    GuardianData guardian() {
        return new GuardianData(guardianName, guardianRelationship, guardianPhone, guardianEmail);
    }

    void updateProfile(String goal, LocalDate birthDate, GuardianData guardian) {
        this.goal = goal;
        this.birthDate = birthDate;
        this.guardianName = guardian.name();
        this.guardianRelationship = guardian.relationship();
        this.guardianPhone = guardian.phone();
        this.guardianEmail = guardian.email();
    }

    void update(String fullName, String email, String whatsappPhone) {
        this.fullName = fullName;
        this.email = email;
        this.whatsappPhone = whatsappPhone;
    }

    void setActive(boolean active) { this.active = active; }
    void linkAccount(UUID userId) { this.userId = userId; }

    /** An acceptance is now in force. */
    void reflectAcceptance(ConsentType type, Instant at) {
        if (type == ConsentType.WHATSAPP) {
            this.whatsappOptInAt = at;
        } else {
            this.dataConsentAt = at;
        }
    }

    /** A revocation: WHATSAPP clears the opt-in; a data consent clears the data date only when no other data consent stays. */
    void reflectRevocation(ConsentType type, boolean otherDataConsentStillActive) {
        if (type == ConsentType.WHATSAPP) {
            this.whatsappOptInAt = null;
        } else if (!otherDataConsentStillActive) {
            this.dataConsentAt = null;
        }
    }

    /** The account stops working and the student waits for anonymization. Consent records and the audit stay. */
    void suspendForAnonymization(Instant at) {
        this.active = false;
        if (this.anonymizationRequestedAt == null) {
            this.anonymizationRequestedAt = at;
        }
    }
}
