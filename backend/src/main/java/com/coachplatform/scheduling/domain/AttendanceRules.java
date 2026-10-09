package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.ALREADY_MARKED;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.CLASS_NOT_STARTED;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.CYCLE_CLOSED;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.INVALID_STATE;

import com.coachplatform.scheduling.api.AttendanceStatus;
import java.time.Clock;
import java.time.Instant;

/**
 * Marking attendance. A class becomes "seen" (and uses one class of the cycle) when marked ATTENDED or NO_SHOW. The
 * result may be switched between those two while the cycle is active (the count does not change), but a mark can
 * never be undone back to SCHEDULED.
 */
public final class AttendanceRules {

    public record Outcome(AttendanceStatus newStatus, boolean consumesClass) {
    }

    private final Clock clock;

    public AttendanceRules(Clock clock) {
        this.clock = clock;
    }

    /**
     * Whether the coach could mark (or switch the result of) this place right now. Derived from {@link #mark}, so the flag the
     * frontend shows can never disagree with what the server would accept.
     */
    public boolean mayMark(AttendanceStatus current, Instant startsAt, boolean cycleActive) {
        return accepts(current, AttendanceStatus.ATTENDED, startsAt, cycleActive)
                || accepts(current, AttendanceStatus.NO_SHOW, startsAt, cycleActive);
    }

    private boolean accepts(AttendanceStatus current, AttendanceStatus target, Instant startsAt, boolean cycleActive) {
        try {
            mark(current, target, startsAt, cycleActive);
            return true;
        } catch (SchedulingRuleException e) {
            return false;
        }
    }

    public Outcome mark(AttendanceStatus current, AttendanceStatus target, Instant startsAt, boolean cycleActive) {
        if (target != AttendanceStatus.ATTENDED && target != AttendanceStatus.NO_SHOW) {
            throw new SchedulingRuleException(INVALID_STATE, "A class can only be marked ATTENDED or NO_SHOW");
        }
        switch (current) {
            case SCHEDULED -> {
                if (clock.instant().isBefore(startsAt)) {
                    throw new SchedulingRuleException(CLASS_NOT_STARTED, "The class has not started yet");
                }
                if (!cycleActive) {
                    throw new SchedulingRuleException(CYCLE_CLOSED, "The class's cycle is no longer active");
                }
                return new Outcome(target, true);
            }
            case ATTENDED, NO_SHOW -> {
                if (current == target) {
                    throw new SchedulingRuleException(ALREADY_MARKED, "The class is already marked " + current);
                }
                if (!cycleActive) {
                    throw new SchedulingRuleException(CYCLE_CLOSED, "The class's cycle is no longer active");
                }
                return new Outcome(target, false);
            }
            default -> throw new SchedulingRuleException(INVALID_STATE, "A " + current + " class cannot be marked");
        }
    }
}
