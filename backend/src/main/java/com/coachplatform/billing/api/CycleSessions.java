package com.coachplatform.billing.api;

import java.util.List;
import java.util.UUID;

/**
 * THE ONLY internal port of the project (an exception to "interfaces only for external boundaries", approved).
 * Billing must know about the attendances of a cycle (to hold its expiry and renewal), while scheduling already depends
 * on billing; this interface, defined here and implemented by scheduling, avoids a circular dependency between modules.
 * It speaks of ATTENDANCES (a student's place in an event), never of events. Callers hold the student's row lock
 * (student first, then the rest), so counts cannot change underneath them.
 */
public interface CycleSessions {

    /** SCHEDULED attendances whose event already started, oldest first. */
    List<PendingSession> pendingMarks(UUID cycleId);

    int pendingMarkCount(UUID cycleId);

    /** SCHEDULED attendances whose event has not started yet, with their event's modality. */
    List<FutureAttendance> futureAttendances(UUID cycleId);

    /**
     * Moves the not-yet-started SCHEDULED attendances of a cycle into another one (renewal on the deadline day).
     * When {@code overrideBy} is not null, the attendances whose event modality differs from {@code newModality} are
     * flagged as overrides (who and why) instead of being refused.
     */
    void moveFutureAttendances(UUID fromCycleId, UUID toCycleId, Modality newModality, UUID overrideBy, String overrideReason);
}
