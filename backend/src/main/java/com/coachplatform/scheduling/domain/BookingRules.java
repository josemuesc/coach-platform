package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.ALREADY_BOOKED;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.BLOCKED;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.CLASS_IN_PAST;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.EVENT_FULL;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.MODALITY_MISMATCH;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.NOT_AVAILABLE;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.NO_ACTIVE_CYCLE;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.OUTSIDE_CYCLE;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.QUOTA_EXCEEDED;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.REASON_REQUIRED;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.SLOT_TAKEN;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.TOO_SOON;

import com.coachplatform.billing.api.Modality;
import com.coachplatform.scheduling.domain.SchedulingRuleException.Code;
import com.coachplatform.scheduling.domain.SlotCalendar.Range;
import com.coachplatform.scheduling.domain.SlotCalendar.Window;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything that must hold for a student to take a place in an event (also for the NEW place of a reschedule).
 * Works on plain values: the service loads the student's cycle and the coach's calendar, asks for the decision,
 * and persists it.
 *
 * <pre>
 *  slot without an event      -> a new event is created with the student's modality
 *  event of the same modality -> PERSONALIZED: never (capacity 1, already taken: SLOT_TAKEN)
 *                                SEMI_PERSONALIZED: joins if there is a free seat, else EVENT_FULL
 *  event of the other modality-> MODALITY_MISMATCH
 *  anything overlapping that is not exactly this slot (e.g. after a duration change) -> SLOT_TAKEN
 * </pre>
 * Only the coach can override MODALITY_MISMATCH / EVENT_FULL / a taken personalized event, with a reason; the cycle,
 * deadline, quota, availability and blocks are never overridable.
 */
public final class BookingRules {

    /** The student's cycle as the booking sees it. classesScheduled counts SCHEDULED places (past unmarked included). */
    public record CycleSnapshot(boolean active, LocalDate endDate, int classesIncluded, int classesUsed,
                                int classesScheduled, Modality modality) {
    }

    /** A coach's override: the rules it may relax are modality and capacity. The reason is mandatory. */
    public record Override(String reason) {
    }

    /**
     * @param overlappingEvents SCHEDULED events overlapping the requested range (each with the student's own status in it)
     * @param byStudent         students must book at least {@code cancelWindowHours} ahead; the coach is exempt
     * @param consumesQuota     false when rescheduling: the place being left frees exactly the place the new one takes
     * @param override          null for students and for coach requests that do not ask to override
     */
    public record Request(Instant startsAt, Duration duration, CycleSnapshot cycle, List<Window> windows,
                          List<Range> blocks, List<EventSnapshot> overlappingEvents, int defaultGroupCapacity,
                          boolean byStudent, int cancelWindowHours, boolean consumesQuota, Override override) {
    }

    public enum Action {
        CREATE_EVENT,
        JOIN_EVENT
    }

    /**
     * What to do. For CREATE_EVENT the event takes eventModality / eventCapacity; for JOIN_EVENT they describe the existing
     * event. overridden = a rule was really bypassed (a coach override that was not needed is not recorded).
     */
    public record Decision(Action action, Range range, Modality eventModality, int eventCapacity, boolean overridden) {
    }

    private final Clock clock;
    private final SlotCalendar slots;

    public BookingRules(Clock clock, SlotCalendar slots) {
        this.clock = clock;
        this.slots = slots;
    }

    /** The capacity a NEW event of this modality gets. Personalized is always 1; semi takes the coach's current default. */
    public static int capacityForNewEvent(Modality modality, int defaultGroupCapacity) {
        return modality == Modality.PERSONALIZED ? 1 : defaultGroupCapacity;
    }

    public Decision validate(Request r) {
        Instant now = clock.instant();
        CycleSnapshot cycle = r.cycle();

        if (cycle == null || !cycle.active()) {
            throw new SchedulingRuleException(NO_ACTIVE_CYCLE, "The student has no active cycle");
        }
        if (r.override() != null && (r.override().reason() == null || r.override().reason().isBlank())) {
            throw new SchedulingRuleException(REASON_REQUIRED, "A reason is required to override the rules");
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

        Decision decision = placeInEvent(r, cycle, range);

        if (r.consumesQuota() && cycle.classesUsed() + cycle.classesScheduled() + 1 > cycle.classesIncluded()) {
            throw new SchedulingRuleException(QUOTA_EXCEEDED,
                    "The cycle has no classes left to schedule (" + cycle.classesUsed() + " used, "
                            + cycle.classesScheduled() + " scheduled, " + cycle.classesIncluded() + " included)");
        }
        return decision;
    }

    private Decision placeInEvent(Request r, CycleSnapshot cycle, Range range) {
        List<EventSnapshot> overlapping = r.overlappingEvents();
        Modality mine = cycle.modality();

        if (overlapping.isEmpty()) {
            return new Decision(Action.CREATE_EVENT, range, mine, capacityForNewEvent(mine, r.defaultGroupCapacity()), false);
        }
        // Something overlaps. Only an event with EXACTLY this range can be joined; any other overlap (two events, or one of
        // another length left over from before a duration change) just means the time is taken - not even an override helps.
        if (overlapping.size() > 1 || !overlapping.get(0).range().equals(range)) {
            throw new SchedulingRuleException(SLOT_TAKEN, "That time is already taken");
        }
        EventSnapshot event = overlapping.get(0);
        if (event.studentAlreadyIn()) {
            throw new SchedulingRuleException(ALREADY_BOOKED, "The student already has a place in that class");
        }

        Code violation = null;
        if (event.modality() != mine) {
            violation = MODALITY_MISMATCH;
        } else if (event.occupied() >= event.capacity()) {
            violation = mine == Modality.PERSONALIZED ? SLOT_TAKEN : EVENT_FULL;
        }
        if (violation != null) {
            if (r.override() == null) {
                throw new SchedulingRuleException(violation, describe(violation, event));
            }
            return new Decision(Action.JOIN_EVENT, range, event.modality(), event.capacity(), true);
        }
        return new Decision(Action.JOIN_EVENT, range, event.modality(), event.capacity(), false);
    }

    private static String describe(Code code, EventSnapshot event) {
        return switch (code) {
            case MODALITY_MISMATCH -> "That time is a " + event.modality() + " class and the student's plan is for the other modality";
            case EVENT_FULL -> "That class is full (" + event.occupied() + " of " + event.capacity() + ")";
            default -> "That time is already taken";
        };
    }
}
