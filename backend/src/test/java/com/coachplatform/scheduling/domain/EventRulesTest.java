package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.domain.SchedulingTestSupport.clockAt;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.codeOf;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.local;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.coachplatform.billing.api.Modality;
import com.coachplatform.scheduling.api.EventStatus;
import com.coachplatform.scheduling.domain.SchedulingRuleException.Code;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventRulesTest {

    private static final Instant STARTS = local("2026-10-13T10:00");

    private static EventRules at(String now) {
        return new EventRules(clockAt(now));
    }

    private static void change(Modality m, EventStatus s, int live, int newCapacity, String now) {
        at(now).requireCapacityChange(m, s, STARTS, live, newCapacity);
    }

    // ---- the valid range of a group capacity: 2-10 ---------------------------------------------------

    @Test
    void groupCapacityMustBeBetweenTwoAndTen() {
        assertThat(EventRules.isValidGroupCapacity(1)).isFalse();
        assertThat(EventRules.isValidGroupCapacity(0)).isFalse();
        assertThat(EventRules.isValidGroupCapacity(-3)).isFalse();
        assertThat(EventRules.isValidGroupCapacity(2)).isTrue();
        assertThat(EventRules.isValidGroupCapacity(4)).isTrue();
        assertThat(EventRules.isValidGroupCapacity(10)).isTrue();
        assertThat(EventRules.isValidGroupCapacity(11)).isFalse();
    }

    @Test
    void theCoachCanSetAnyCapacityInRangeBeforeTheEventStarts() {
        for (int capacity : new int[] {2, 3, 7, 10}) {
            assertThatCode(() -> change(Modality.SEMI_PERSONALIZED, EventStatus.SCHEDULED, 1, capacity, "2026-10-12T08:00"))
                    .as("capacity " + capacity).doesNotThrowAnyException();
        }
    }

    @Test
    void aCapacityOutsideTwoToTenIsRejected() {
        for (int capacity : new int[] {-1, 0, 1, 11, 50}) {
            assertThat(codeOf(() -> change(Modality.SEMI_PERSONALIZED, EventStatus.SCHEDULED, 0, capacity, "2026-10-12T08:00")))
                    .as("capacity " + capacity).isEqualTo(Code.INVALID_CAPACITY);
        }
    }

    @Test
    void aPersonalizedEventAlwaysHasCapacityOne() {
        assertThat(codeOf(() -> change(Modality.PERSONALIZED, EventStatus.SCHEDULED, 1, 2, "2026-10-12T08:00"))).isEqualTo(Code.CAPACITY_NOT_CONFIGURABLE);
        assertThat(codeOf(() -> change(Modality.PERSONALIZED, EventStatus.SCHEDULED, 1, 1, "2026-10-12T08:00"))).isEqualTo(Code.CAPACITY_NOT_CONFIGURABLE);
    }

    // ---- reducing below the people already inside ------------------------------------------------------

    @Test
    void reducingBelowTheCurrentAttendeesIsRejectedWithAClearMessage() {
        try {
            change(Modality.SEMI_PERSONALIZED, EventStatus.SCHEDULED, 3, 2, "2026-10-12T08:00");
            throw new AssertionError("expected a rejection");
        } catch (SchedulingRuleException e) {
            assertThat(e.code()).isEqualTo(Code.CAPACITY_BELOW_OCCUPANCY);
            assertThat(e.getMessage()).contains("already has 3 attendee(s)").contains("reducing its capacity to 2");
        }
    }

    @Test
    void reducingExactlyToTheCurrentAttendeesIsFine() {
        assertThatCode(() -> change(Modality.SEMI_PERSONALIZED, EventStatus.SCHEDULED, 3, 3, "2026-10-12T08:00")).doesNotThrowAnyException();
    }

    @Test
    void anEventOverbookedByAnOverrideCannotBeChangedUntilItFits() {
        // capacity 4 with 5 people inside (one was added by override): even raising to 4 is still below the occupancy
        assertThat(codeOf(() -> change(Modality.SEMI_PERSONALIZED, EventStatus.SCHEDULED, 5, 4, "2026-10-12T08:00"))).isEqualTo(Code.CAPACITY_BELOW_OCCUPANCY);
        assertThatCode(() -> change(Modality.SEMI_PERSONALIZED, EventStatus.SCHEDULED, 5, 6, "2026-10-12T08:00")).doesNotThrowAnyException();
    }

    // ---- only before the event starts ----------------------------------------------------------------------

    @Test
    void theCapacityCanOnlyChangeBeforeTheEventStarts() {
        assertThatCode(() -> change(Modality.SEMI_PERSONALIZED, EventStatus.SCHEDULED, 1, 5, "2026-10-13T09:59:59")).doesNotThrowAnyException();
        assertThat(codeOf(() -> change(Modality.SEMI_PERSONALIZED, EventStatus.SCHEDULED, 1, 5, "2026-10-13T10:00"))).isEqualTo(Code.EVENT_ALREADY_STARTED);
        assertThat(codeOf(() -> change(Modality.SEMI_PERSONALIZED, EventStatus.SCHEDULED, 1, 5, "2026-10-13T12:00"))).isEqualTo(Code.EVENT_ALREADY_STARTED);
    }

    @Test
    void aCancelledEventCannotChangeItsCapacity() {
        assertThat(codeOf(() -> change(Modality.SEMI_PERSONALIZED, EventStatus.CANCELLED, 0, 5, "2026-10-12T08:00"))).isEqualTo(Code.INVALID_STATE);
    }

    // ---- cancelling the whole event ---------------------------------------------------------------------------

    @Test
    void cancellingTheWholeEventNeedsAReasonAndAScheduledEvent() {
        var rules = at("2026-10-12T08:00");
        assertThatCode(() -> rules.requireCoachMayCancelEvent(EventStatus.SCHEDULED, "Entrenador enfermo")).doesNotThrowAnyException();
        assertThat(codeOf(() -> rules.requireCoachMayCancelEvent(EventStatus.SCHEDULED, " "))).isEqualTo(Code.REASON_REQUIRED);
        assertThat(codeOf(() -> rules.requireCoachMayCancelEvent(EventStatus.SCHEDULED, null))).isEqualTo(Code.REASON_REQUIRED);
        assertThat(codeOf(() -> rules.requireCoachMayCancelEvent(EventStatus.CANCELLED, "x"))).isEqualTo(Code.INVALID_STATE);
    }

    @Test
    void anEventWithNoLiveAttendanceLeftIsCancelledByTheSystem() {
        assertThat(EventRules.shouldAutoCancel(0)).isTrue();
        assertThat(EventRules.shouldAutoCancel(1)).isFalse();
        assertThat(EventRules.shouldAutoCancel(4)).isFalse();
    }
}
