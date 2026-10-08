package com.coachplatform.tenant;

import java.util.UUID;
import java.util.function.Supplier;

/** Holds the tenant (coach) of the current request. Set only from the validated JWT, never from client input. */
public final class TenantContext {

    /** Matches no real coach: queries return nothing and inserts fail on the FK. */
    public static final UUID NO_TENANT = new UUID(0L, 0L);

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID coachId) {
        CURRENT.set(coachId);
    }

    public static UUID get() {
        UUID id = CURRENT.get();
        return id != null ? id : NO_TENANT;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Runs an action as the given tenant and restores the previous one. Only for server-derived tenants
     * (a job iterating coaches, an invitation looked up by its token hash), never for ids sent by a client.
     * Start the transaction INSIDE the action: the Hibernate session picks the tenant when it is opened.
     */
    public static <T> T callAs(UUID coachId, Supplier<T> action) {
        UUID previous = CURRENT.get();
        CURRENT.set(coachId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runAs(UUID coachId, Runnable action) {
        callAs(coachId, () -> {
            action.run();
            return null;
        });
    }
}
