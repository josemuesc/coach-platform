package com.coachplatform.coach;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "coach_settings")
public class CoachSettings {

    @Id
    @Column(name = "coach_id")
    private UUID coachId;

    @Column(name = "cancel_window_hours", nullable = false)
    private int cancelWindowHours = 2;

    @Column(name = "expiring_soon_days", nullable = false)
    private int expiringSoonDays = 5;

    @Column(name = "expiring_soon_classes", nullable = false)
    private int expiringSoonClasses = 1;

    @Column(name = "max_extension_days", nullable = false)
    private int maxExtensionDays = 60;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CoachSettings() {
    }

    public CoachSettings(UUID coachId) {
        this.coachId = coachId;
    }

    public UUID getCoachId() { return coachId; }
    public int getCancelWindowHours() { return cancelWindowHours; }
    public int getExpiringSoonDays() { return expiringSoonDays; }
    public int getExpiringSoonClasses() { return expiringSoonClasses; }
    public int getMaxExtensionDays() { return maxExtensionDays; }
}
