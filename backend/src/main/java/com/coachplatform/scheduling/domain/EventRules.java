package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.CAPACITY_BELOW_OCCUPANCY;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.CAPACITY_NOT_CONFIGURABLE;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.EVENT_ALREADY_STARTED;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.INVALID_CAPACITY;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.INVALID_STATE;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.REASON_REQUIRED;

import com.coachplatform.billing.api.Modality;
import com.coachplatform.scheduling.api.EventPhase;
import com.coachplatform.scheduling.api.EventStatus;
import java.time.Clock;
import java.time.Instant;

/** Rules about an EVENT itself (as opposed to one student's place in it): its capacity and its cancellation. */
public final class EventRules {

    public static final int MIN_GROUP_CAPACITY = 2;
    public static final int MAX_GROUP_CAPACITY = 10;

    private final Clock clock;

    public EventRules(Clock clock) {
        this.clock = clock;
    }

    /** PAST from the end instant on, NOW from the start instant until the end, UPCOMING before the start. */
    public EventPhase phase(Instant startsAt, Instant endsAt) {
        Instant now = clock.instant();
        if (!now.isBefore(endsAt)) {
            return EventPhase.PAST;
        }
        return now.isBefore(startsAt) ? EventPhase.UPCOMING : EventPhase.NOW;
    }

    /** The valid range of a semi-personalized capacity, also used for the coach's default. */
    public static boolean isValidGroupCapacity(int capacity) {
        return capacity >= MIN_GROUP_CAPACITY && capacity <= MAX_GROUP_CAPACITY;
    }

    /**
     * The coach changes the capacity of one event. Only a semi-personalized, still scheduled event that has not started, to a
     * value in 2-10 that its current attendees still fit in. The message says how many are inside, so the coach knows what to do.
     */
    public void requireCapacityChange(Modality modality, EventStatus status, Instant startsAt, int liveAttendees, int newCapacity) {
        if (status != EventStatus.SCHEDULED) {
            throw new SchedulingRuleException(INVALID_STATE, "Only a scheduled event can change its capacity");
        }
        if (!clock.instant().isBefore(startsAt)) {
            throw new SchedulingRuleException(EVENT_ALREADY_STARTED, "The capacity can only be changed before the event starts");
        }
        if (modality == Modality.PERSONALIZED) {
            throw new SchedulingRuleException(CAPACITY_NOT_CONFIGURABLE, "A personalized class always has capacity 1");
        }
        if (!isValidGroupCapacity(newCapacity)) {
            throw new SchedulingRuleException(INVALID_CAPACITY,
                    "The capacity must be between " + MIN_GROUP_CAPACITY + " and " + MAX_GROUP_CAPACITY);
        }
        if (liveAttendees > newCapacity) {
            throw new SchedulingRuleException(CAPACITY_BELOW_OCCUPANCY, "The event already has " + liveAttendees
                    + " attendee(s); remove some before reducing its capacity to " + newCapacity);
        }
    }

    /** The coach cancels the whole event: only a scheduled one, with a mandatory reason. */
    public void requireCoachMayCancelEvent(EventStatus status, String reason) {
        if (status != EventStatus.SCHEDULED) {
            throw new SchedulingRuleException(INVALID_STATE, "Only a scheduled event can be cancelled");
        }
        if (reason == null || reason.isBlank()) {
            throw new SchedulingRuleException(REASON_REQUIRED, "A reason is required to cancel an event");
        }
    }

    /**
     * An event with no live attendance (booked or already seen) left is cancelled by the system. An event with any class
     * already marked is never emptied: marked places stay live.
     */
    public static boolean shouldAutoCancel(int liveAttendees) {
        return liveAttendees == 0;
    }
}
