package com.coachplatform.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Base class for every business entity. Hibernate adds {@code coach_id = :tenant} to all queries
 * and fills it on insert, so repositories cannot forget the tenant filter.
 */
@MappedSuperclass
public abstract class TenantScopedEntity {

    @TenantId
    @Column(name = "coach_id", nullable = false, updatable = false)
    private UUID coachId;

    public UUID getCoachId() {
        return coachId;
    }
}
