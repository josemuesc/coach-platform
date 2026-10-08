package com.coachplatform.billing;

import com.coachplatform.billing.api.Modality;
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

@Entity
@Table(name = "plan")
class Plan extends TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "classes_included", nullable = false)
    private int classesIncluded;

    @Column(name = "price_cop", nullable = false)
    private long priceCop;

    @Column(nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Modality modality;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Plan() {
    }

    Plan(String name, int classesIncluded, long priceCop, Modality modality) {
        this.modality = modality;
        this.name = name;
        this.classesIncluded = classesIncluded;
        this.priceCop = priceCop;
    }

    UUID getId() { return id; }
    String getName() { return name; }
    int getClassesIncluded() { return classesIncluded; }
    long getPriceCop() { return priceCop; }
    boolean isActive() { return active; }
    Modality getModality() { return modality; }

    void update(String name, int classesIncluded, long priceCop, Modality modality) {
        this.modality = modality;
        this.name = name;
        this.classesIncluded = classesIncluded;
        this.priceCop = priceCop;
    }

    void setActive(boolean active) { this.active = active; }
}
