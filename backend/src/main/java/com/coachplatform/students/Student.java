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

    void update(String fullName, String email, String whatsappPhone) {
        this.fullName = fullName;
        this.email = email;
        this.whatsappPhone = whatsappPhone;
    }

    void setActive(boolean active) { this.active = active; }
    void linkAccount(UUID userId) { this.userId = userId; }
}
