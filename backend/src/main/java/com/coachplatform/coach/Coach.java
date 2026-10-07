package com.coachplatform.coach;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "coach")
public class Coach {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Future use only (always null in the MVP). Never used to grant access. */
    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(nullable = false)
    private String name;

    @Column(name = "brand_name", nullable = false)
    private String brandName;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "primary_color")
    private String primaryColor;

    private String phone;

    @Column(nullable = false)
    private String timezone = "America/Bogota";

    @Column(name = "subscription_plan", nullable = false)
    private String subscriptionPlan = "FREE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Coach() {
    }

    public Coach(String name, String brandName) {
        this.name = name;
        this.brandName = brandName;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getBrandName() { return brandName; }
}
