package com.coachplatform.tenant;

import java.util.UUID;

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
}
