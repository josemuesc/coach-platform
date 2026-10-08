package com.coachplatform.billing;

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

/** Audit row: who moved a cycle's deadline, from when to when, when and why. */
@Entity
@Table(name = "cycle_extension")
class CycleExtensionRecord extends TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cycle_id", nullable = false, updatable = false)
    private UUID cycleId;

    @Column(name = "previous_end_date", nullable = false, updatable = false)
    private LocalDate previousEndDate;

    @Column(name = "new_end_date", nullable = false, updatable = false)
    private LocalDate newEndDate;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(name = "extended_by", nullable = false, updatable = false)
    private UUID extendedBy;

    @Column(name = "extended_at", nullable = false, updatable = false)
    private Instant extendedAt;

    protected CycleExtensionRecord() {
    }

    CycleExtensionRecord(UUID cycleId, LocalDate previousEndDate, LocalDate newEndDate, String reason,
                         UUID extendedBy, Instant extendedAt) {
        this.cycleId = cycleId;
        this.previousEndDate = previousEndDate;
        this.newEndDate = newEndDate;
        this.reason = reason;
        this.extendedBy = extendedBy;
        this.extendedAt = extendedAt;
    }
}
