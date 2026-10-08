package com.coachplatform.tenant;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the ONLY components allowed to read data across tenants (plain JDBC, no tenant filter), e.g. the daily job
 * that finds expired cycles of every coach, or the lookup of an invitation's coach by token hash.
 * Rules (enforced by ArchUnit): such a class must be package-private, must never be used by a controller, and is the
 * only kind of class that may touch JDBC. Callers then switch to the found tenant with {@link TenantContext#runAs}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CrossTenantAccess {
}
