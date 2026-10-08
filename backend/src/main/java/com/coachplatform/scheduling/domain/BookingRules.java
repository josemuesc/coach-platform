package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.BLOCKED;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.CLASS_IN_PAST;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.NOT_AVAILABLE;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.NO_ACTIVE_CYCLE;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.OUTSIDE_CYCLE;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.QUOTA_EXCEEDED;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.SLOT_TAKEN;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.TOO_SOON;

import com.coachplatform.scheduling.domain.SlotCalendar.Range;
import com.coachplatform.scheduling.domain.SlotCalendar.Window;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything that must hold for a class to be booked (also for the NEW slot of a reschedule). Works on plain values:
 * the service translates entities, loads the cycle and the coach's calendar, and persists the outcome.
 */
public final class BookingRules {

    /** The student's cycle as the booking sees it. classesScheduled counts SCHEDULED classes (past unmarked included). */
    public record CycleSnapshot(boolean active, LocalDate endDate, int classesIncluded, int classesUsed,
                                int classesScheduled) {
    }

    /**
     * @param byStudent        students must book at least {@code cancelWindowHours} ahead; the coach is exempt
     * @param consumesQuota    false when rescheduling: the class being replaced frees exactly the place the new one takes
     * @param booked           the coach's SCHEDULED ranges (for a reschedule, WITHOUT the class being replaced)
     */
    public record Request(Instant startsAt, Duration duration, CycleSnapshot cycle, List<Window> windows,
                          List<Range> blocks, List<Range> booked, boolean byStudent, int cancelWindowHours,
                          boolean consumesQuota) {
    }

    private final Clock clock;
    private final SlotCalendar slots;

    public BookingRules(Clock clock, SlotCalendar slots) {
        this.clock = clock;
        this.slots = slots;
    }

    /** Returns the class's time range, or throws the first rule that fails. */
    public Range validate(Request r) {
        Instant now = clock.instant();
        CycleSnapshot cycle = r.cycle();

        if (cycle == null || !cycle.active()) {
            throw new SchedulingRuleException(NO_ACTIVE_CYCLE, "The student has no active cycle");
        }
        if (!r.startsAt().isAfter(now)) {
            throw new SchedulingRuleException(CLASS_IN_PAST, "The class must be in the future");
        }
        if (r.byStudent() && r.startsAt().isBefore(now.plus(Duration.ofHours(r.cancelWindowHours())))) {
            throw new SchedulingRuleException(TOO_SOON,
                    "Classes must be booked at least " + r.cancelWindowHours() + " hours ahead");
        }
        LocalDate classDay = r.startsAt().atZone(slots.zone()).toLocalDate();
        if (classDay.isAfter(cycle.endDate())) {
            throw new SchedulingRuleException(OUTSIDE_CYCLE,
                    "The class must be on or before the cycle deadline (" + cycle.endDate() + ")");
        }
        if (!slots.isSlotStart(r.startsAt(), r.windows(), r.duration())) {
            throw new SchedulingRuleException(NOT_AVAILABLE, "That time is not one of the coach's available slots");
        }
        Range range = new Range(r.startsAt(), r.startsAt().plus(r.duration()));
        if (r.blocks().stream().anyMatch(range::overlaps)) {
            throw new SchedulingRuleException(BLOCKED, "The coach is not available at that time");
        }
        if (r.booked().stream().anyMatch(range::overlaps)) {
            throw new SchedulingRuleException(SLOT_TAKEN, "That time is already taken");
        }
        if (r.consumesQuota() && cycle.classesUsed() + cycle.classesScheduled() + 1 > cycle.classesIncluded()) {
            throw new SchedulingRuleException(QUOTA_EXCEEDED,
                    "The cycle has no classes left to schedule (" + cycle.classesUsed() + " used, "
                            + cycle.classesScheduled() + " scheduled, " + cycle.classesIncluded() + " included)");
        }
        return range;
    }
}
