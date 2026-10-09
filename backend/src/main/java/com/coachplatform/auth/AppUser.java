package com.coachplatform.auth;

import com.coachplatform.auth.api.PasswordChangeMethod;
import com.coachplatform.security.UserRole;
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

/** Not tenant-filtered on purpose: login looks users up by email before any tenant is known. */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "coach_id", nullable = false, updatable = false)
    private UUID coachId;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Column(nullable = false)
    private boolean active = true;

    /** Null until the password changes for the first time after registration / invitation. Millisecond precision. */
    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "password_change_method")
    private PasswordChangeMethod passwordChangeMethod;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AppUser() {
    }

    public AppUser(UUID coachId, String email, String passwordHash, UserRole role) {
        this.coachId = coachId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public UUID getId() { return id; }
    public UUID getCoachId() { return coachId; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public UserRole getRole() { return role; }
    public boolean isActive() { return active; }

    public void deactivate() {
        this.active = false;
    }

    public Instant getPasswordChangedAt() { return passwordChangedAt; }
    public PasswordChangeMethod getPasswordChangeMethod() { return passwordChangeMethod; }

    /** What a token must carry to still be valid: 0 while the password never changed, else the change time in epoch millis. */
    public long passwordEpoch() {
        return passwordChangedAt == null ? 0L : passwordChangedAt.toEpochMilli();
    }

    /** Every password change goes through here, so the previous sessions stop working (their epoch no longer matches). */
    public void changePassword(String newHash, PasswordChangeMethod method, Instant now) {
        this.passwordHash = newHash;
        this.passwordChangeMethod = method;
        this.passwordChangedAt = now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }
}
