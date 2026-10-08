package com.coachplatform.billing.api;

import java.util.List;
import java.util.UUID;

/**
 * THE ONLY internal port of the project (an exception to "interfaces only for external boundaries", approved).
 * Billing must know about the classes of a cycle (to hold its expiry and renewal), while scheduling already depends
 * on billing; this interface, defined here and implemented by scheduling, avoids a circular dependency between modules.
 * Callers hold the student's row lock (student first, then the sessions), so counts cannot change underneath them.
 */
public interface CycleSessions {

    /** SCHEDULED classes that already started and still need to be marked, oldest first. */
    List<PendingSession> pendingMarks(UUID cycleId);

    int pendingMarkCount(UUID cycleId);

    /** SCHEDULED classes that have not started yet. */
    int futureScheduledCount(UUID cycleId);

    /** Moves the not-yet-started SCHEDULED classes of a cycle into another one (renewal on the deadline day). */
    void moveFutureSessions(UUID fromCycleId, UUID toCycleId);
}
