package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.domain.SchedulingTestSupport.BOGOTA;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.clockAt;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.codeOf;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.local;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.coachplatform.billing.api.Modality;
import com.coachplatform.scheduling.domain.BookingRules.Action;
import com.coachplatform.scheduling.domain.BookingRules.CycleSnapshot;
import com.coachplatform.scheduling.domain.BookingRules.Decision;
import com.coachplatform.scheduling.domain.BookingRules.Override;
import com.coachplatform.scheduling.domain.BookingRules.Request;
import com.coachplatform.scheduling.domain.SchedulingRuleException.Code;
import com.coachplatform.scheduling.domain.SlotCalendar.Range;
import com.coachplatform.scheduling.domain.SlotCalendar.Window;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookingRulesTest {

    private static final Duration HOUR = Duration.ofMinutes(60);
    // every day 06:00-20:00, so the tests can pick any day without caring about the weekday
    private static final List<Window> ALL_WEEK = java.util.Arrays.stream(DayOfWeek.values())
            .map(d -> new Window(d, LocalTime.of(6, 0), LocalTime.of(20, 0))).toList();
    private static final LocalDate CYCLE_END = LocalDate.of(2026, 11, 6);
    private static final int DEFAULT_GROUP = 4;
    private static final String AT = "2026-10-13T10:00";   // the slot used by the modality tests

    private static BookingRules rulesAt(String now) {
        return new BookingRules(clockAt(now), new SlotCalendar(BOGOTA));
    }

    private static CycleSnapshot cycle(Modality modality) {
        return new CycleSnapshot(true, CYCLE_END, 8, 0, 0, modality);
    }

    private static CycleSnapshot cycle(int included, int used, int scheduled) {
        return new CycleSnapshot(true, CYCLE_END, included, used, scheduled, Modality.PERSONALIZED);
    }

    private static Range range(String startLocal) {
        Instant s = local(startLocal);
        return new Range(s, s.plus(HOUR));
    }

    private static EventSnapshot event(Modality modality, int capacity, int occupied) {
        return new EventSnapshot(range(AT), modality, capacity, occupied, false);
    }

    private static Request request(String startsLocal, CycleSnapshot cycle, boolean byStudent, boolean consumesQuota,
                                   List<Range> blocks, List<EventSnapshot> events, Override override) {
        return new Request(local(startsLocal), HOUR, cycle, ALL_WEEK, blocks, events, DEFAULT_GROUP, byStudent, 2, consumesQuota, override);
    }

    private static Request student(String startsLocal) {
        return request(startsLocal, cycle(8, 0, 0), true, true, List.of(), List.of(), null);
    }

    private static Request join(CycleSnapshot cycle, EventSnapshot event, Override override) {
        return request(AT, cycle, true, true, List.of(), List.of(event), override);
    }

    private static Decision decide(Request r) {
        return rulesAt("2026-10-12T08:00").validate(r);
    }

    private static Code codeOf(Request r) {
        return SchedulingTestSupport.codeOf(() -> decide(r));
    }

    // ================================================================= the basics (per attendance)

    @Test
    void anEmptySlotCreatesAnEventWithTheStudentsModality() {
        Decision d = rulesAt("2026-10-12T08:00").validate(student("2026-10-13T10:00"));
        assertThat(d.action()).isEqualTo(Action.CREATE_EVENT);
        assertThat(d.range().start()).isEqualTo(local("2026-10-13T10:00"));
        assertThat(d.range().end()).isEqualTo(local("2026-10-13T11:00"));
        assertThat(d.overridden()).isFalse();
    }

    @Test
    void withoutAnActiveCycleNothingCanBeBooked() {
        var inactive = new CycleSnapshot(false, CYCLE_END, 8, 0, 0, Modality.PERSONALIZED);
        assertThat(codeOf(request(AT, inactive, true, true, List.of(), List.of(), null))).isEqualTo(Code.NO_ACTIVE_CYCLE);
        assertThat(codeOf(request(AT, null, true, true, List.of(), List.of(), null))).isEqualTo(Code.NO_ACTIVE_CYCLE);
    }

    @Test
    void aClassInThePastIsRejected() {
        assertThat(codeOf(student("2026-10-12T07:00"))).isEqualTo(Code.CLASS_IN_PAST);
        assertThat(codeOf(student("2026-10-12T08:00"))).isEqualTo(Code.CLASS_IN_PAST);
    }

    @Test
    void aStudentMustBookAtLeastTheCancellationWindowAheadButTheCoachNeedNot() {
        assertThatCode(() -> decide(student("2026-10-12T10:00"))).doesNotThrowAnyException();       // exactly 2 h
        assertThat(codeOf(student("2026-10-12T09:00"))).isEqualTo(Code.TOO_SOON);
        var byCoach = request("2026-10-12T09:00", cycle(8, 0, 0), false, true, List.of(), List.of(), null);
        assertThatCode(() -> decide(byCoach)).doesNotThrowAnyException();
    }

    @Test
    void theCycleDeadlineDayIsBookableEvenInTheEveningAndTheDayAfterIsNot() {
        assertThatCode(() -> rulesAt("2026-11-01T08:00").validate(student("2026-11-06T19:00"))).doesNotThrowAnyException();
        assertThat(SchedulingTestSupport.codeOf(() -> rulesAt("2026-11-01T08:00").validate(student("2026-11-07T06:00")))).isEqualTo(Code.OUTSIDE_CYCLE);
    }

    @Test
    void aRescheduleIsLimitedToTheCycleAndDoesNotNeedAFreeQuota() {
        var fullCycle = cycle(8, 5, 3);
        assertThatCode(() -> rulesAt("2026-11-01T08:00").validate(
                request("2026-11-05T10:00", fullCycle, true, false, List.of(), List.of(), null))).doesNotThrowAnyException();
        assertThat(SchedulingTestSupport.codeOf(() -> rulesAt("2026-11-01T08:00").validate(
                request("2026-11-09T10:00", fullCycle, true, false, List.of(), List.of(), null)))).isEqualTo(Code.OUTSIDE_CYCLE);
    }

    @Test
    void timesOutsideTheWindowsOrOffTheGridAreNotAvailableAndBlocksAreRespected() {
        assertThat(codeOf(student("2026-10-13T21:00"))).isEqualTo(Code.NOT_AVAILABLE);
        assertThat(codeOf(student("2026-10-13T10:30"))).isEqualTo(Code.NOT_AVAILABLE);
        assertThat(codeOf(request(AT, cycle(8, 0, 0), true, true, List.of(range("2026-10-13T09:30")), List.of(), null))).isEqualTo(Code.BLOCKED);
    }

    @Test
    void usedPlusScheduledCanNeverExceedWhatTheCycleIncludes() {
        assertThatCode(() -> decide(request(AT, cycle(8, 5, 2), true, true, List.of(), List.of(), null))).doesNotThrowAnyException();
        assertThat(codeOf(request(AT, cycle(8, 5, 3), true, true, List.of(), List.of(), null))).isEqualTo(Code.QUOTA_EXCEEDED);
        assertThat(codeOf(request(AT, cycle(8, 8, 0), true, true, List.of(), List.of(), null))).isEqualTo(Code.QUOTA_EXCEEDED);
    }

    // ================================================================= capacity of a NEW event

    @Test
    void aNewPersonalizedEventAlwaysHasCapacityOne() {
        Decision d = decide(request(AT, cycle(Modality.PERSONALIZED), true, true, List.of(), List.of(), null));
        assertThat(d.eventModality()).isEqualTo(Modality.PERSONALIZED);
        assertThat(d.eventCapacity()).isEqualTo(1);
    }

    @Test
    void aNewSemiPersonalizedEventTakesTheCoachsCurrentDefaultCapacity() {
        Decision d = decide(request(AT, cycle(Modality.SEMI_PERSONALIZED), true, true, List.of(), List.of(), null));
        assertThat(d.eventModality()).isEqualTo(Modality.SEMI_PERSONALIZED);
        assertThat(d.eventCapacity()).isEqualTo(DEFAULT_GROUP);

        var withSix = new Request(local(AT), HOUR, cycle(Modality.SEMI_PERSONALIZED), ALL_WEEK, List.of(), List.of(), 6, true, 2, true, null);
        assertThat(decide(withSix).eventCapacity()).isEqualTo(6);
        var personalizedIgnoresIt = new Request(local(AT), HOUR, cycle(Modality.PERSONALIZED), ALL_WEEK, List.of(), List.of(), 6, true, 2, true, null);
        assertThat(decide(personalizedIgnoresIt).eventCapacity()).isEqualTo(1);
        assertThat(BookingRules.capacityForNewEvent(Modality.SEMI_PERSONALIZED, 9)).isEqualTo(9);
        assertThat(BookingRules.capacityForNewEvent(Modality.PERSONALIZED, 9)).isEqualTo(1);
    }

    // ================================================================= the modality x event-state matrix (students)

    @Test
    void aPersonalizedStudentCannotTakeAnEventThatIsAlreadyTaken() {
        var me = cycle(Modality.PERSONALIZED);
        assertThat(codeOf(join(me, event(Modality.PERSONALIZED, 1, 1), null))).isEqualTo(Code.SLOT_TAKEN);
        assertThat(codeOf(join(me, event(Modality.SEMI_PERSONALIZED, 4, 1), null))).isEqualTo(Code.MODALITY_MISMATCH);   // even with free seats
        assertThat(codeOf(join(me, event(Modality.SEMI_PERSONALIZED, 4, 4), null))).isEqualTo(Code.MODALITY_MISMATCH);   // or a full one
    }

    @Test
    void aSemiPersonalizedStudentJoinsASemiEventWithASeatAndNothingElse() {
        var me = cycle(Modality.SEMI_PERSONALIZED);

        Decision d = decide(join(me, event(Modality.SEMI_PERSONALIZED, 4, 3), null));
        assertThat(d.action()).isEqualTo(Action.JOIN_EVENT);
        assertThat(d.eventModality()).isEqualTo(Modality.SEMI_PERSONALIZED);
        assertThat(d.eventCapacity()).isEqualTo(4);
        assertThat(d.overridden()).isFalse();

        assertThat(codeOf(join(me, event(Modality.SEMI_PERSONALIZED, 4, 4), null))).isEqualTo(Code.EVENT_FULL);
        assertThat(codeOf(join(me, event(Modality.PERSONALIZED, 1, 1), null))).isEqualTo(Code.MODALITY_MISMATCH);
    }

    @Test
    void joiningAnEventThatReducedItsCapacityBelowItsOccupancyStaysFull() {
        // capacity was reduced to 2 by the coach after 3 people were in (or an override added one): it is full, not negative
        assertThat(codeOf(join(cycle(Modality.SEMI_PERSONALIZED), event(Modality.SEMI_PERSONALIZED, 2, 3), null))).isEqualTo(Code.EVENT_FULL);
    }

    @Test
    void aStudentAlreadyInTheEventCannotTakeASecondPlace() {
        var already = new EventSnapshot(range(AT), Modality.SEMI_PERSONALIZED, 4, 2, true);
        assertThat(codeOf(join(cycle(Modality.SEMI_PERSONALIZED), already, null))).isEqualTo(Code.ALREADY_BOOKED);
        assertThat(codeOf(join(cycle(Modality.SEMI_PERSONALIZED), already, new Override("x")))).isEqualTo(Code.ALREADY_BOOKED);   // not overridable
    }

    @Test
    void anOverlapThatIsNotExactlyThisSlotJustMeansTheTimeIsTaken() {
        // an older event of another length (before a duration change), and two overlapping events: never joinable, not even by override
        var longer = new EventSnapshot(new Range(local("2026-10-13T09:30"), local("2026-10-13T10:30")), Modality.SEMI_PERSONALIZED, 4, 1, false);
        assertThat(codeOf(request(AT, cycle(Modality.SEMI_PERSONALIZED), true, true, List.of(), List.of(longer), null))).isEqualTo(Code.SLOT_TAKEN);
        assertThat(codeOf(request(AT, cycle(Modality.SEMI_PERSONALIZED), false, true, List.of(), List.of(longer), new Override("x")))).isEqualTo(Code.SLOT_TAKEN);

        var one = event(Modality.SEMI_PERSONALIZED, 4, 1);
        assertThat(codeOf(request(AT, cycle(Modality.SEMI_PERSONALIZED), true, true, List.of(), List.of(one, longer), null))).isEqualTo(Code.SLOT_TAKEN);
    }

    // ================================================================= coach override

    @Test
    void theCoachCanAddAStudentToAFullEventWithAReasonAndItIsFlagged() {
        var byCoach = request(AT, cycle(Modality.SEMI_PERSONALIZED), false, true, List.of(), List.of(event(Modality.SEMI_PERSONALIZED, 4, 4)), new Override("Pidió quedarse"));
        Decision d = decide(byCoach);
        assertThat(d.action()).isEqualTo(Action.JOIN_EVENT);
        assertThat(d.overridden()).isTrue();
        assertThat(d.eventCapacity()).as("the event keeps its own capacity").isEqualTo(4);
    }

    @Test
    void theCoachCanAddAStudentToAnEventOfTheOtherModalityAndToATakenPersonalizedOne() {
        Override o = new Override("Caso especial");
        var semiStudent = cycle(Modality.SEMI_PERSONALIZED);
        var personalizedStudent = cycle(Modality.PERSONALIZED);

        assertThat(decide(request(AT, semiStudent, false, true, List.of(), List.of(event(Modality.PERSONALIZED, 1, 1)), o)).overridden()).isTrue();
        assertThat(decide(request(AT, personalizedStudent, false, true, List.of(), List.of(event(Modality.SEMI_PERSONALIZED, 4, 2)), o)).overridden()).isTrue();
        assertThat(decide(request(AT, personalizedStudent, false, true, List.of(), List.of(event(Modality.PERSONALIZED, 1, 1)), o)).overridden()).isTrue();
    }

    @Test
    void anOverrideThatIsNotNeededIsNotRecorded() {
        var byCoach = request(AT, cycle(Modality.SEMI_PERSONALIZED), false, true, List.of(), List.of(event(Modality.SEMI_PERSONALIZED, 4, 2)), new Override("por si acaso"));
        assertThat(decide(byCoach).overridden()).isFalse();
        var emptySlot = request(AT, cycle(Modality.PERSONALIZED), false, true, List.of(), List.of(), new Override("por si acaso"));
        assertThat(decide(emptySlot).overridden()).isFalse();
    }

    @Test
    void anOverrideNeedsAReason() {
        var full = event(Modality.SEMI_PERSONALIZED, 4, 4);
        assertThat(codeOf(request(AT, cycle(Modality.SEMI_PERSONALIZED), false, true, List.of(), List.of(full), new Override(null)))).isEqualTo(Code.REASON_REQUIRED);
        assertThat(codeOf(request(AT, cycle(Modality.SEMI_PERSONALIZED), false, true, List.of(), List.of(full), new Override("   ")))).isEqualTo(Code.REASON_REQUIRED);
    }

    @Test
    void anOverrideRelaxesOnlyModalityAndCapacityNeverTheCycleTheQuotaTheCalendarOrBlocks() {
        Override o = new Override("forzar");
        var full = List.of(event(Modality.SEMI_PERSONALIZED, 4, 4));

        var inactive = new CycleSnapshot(false, CYCLE_END, 8, 0, 0, Modality.SEMI_PERSONALIZED);
        assertThat(codeOf(request(AT, inactive, false, true, List.of(), full, o))).isEqualTo(Code.NO_ACTIVE_CYCLE);

        var noQuota = new CycleSnapshot(true, CYCLE_END, 8, 5, 3, Modality.SEMI_PERSONALIZED);
        assertThat(codeOf(request(AT, noQuota, false, true, List.of(), full, o))).isEqualTo(Code.QUOTA_EXCEEDED);

        assertThat(codeOf(request("2026-11-09T10:00", cycle(Modality.SEMI_PERSONALIZED), false, true, List.of(), List.of(), o))).isEqualTo(Code.OUTSIDE_CYCLE);
        assertThat(codeOf(request("2026-10-13T10:30", cycle(Modality.SEMI_PERSONALIZED), false, true, List.of(), List.of(), o))).isEqualTo(Code.NOT_AVAILABLE);
        assertThat(codeOf(request(AT, cycle(Modality.SEMI_PERSONALIZED), false, true, List.of(range("2026-10-13T09:30")), full, o))).isEqualTo(Code.BLOCKED);
    }

    // ================================================================= reschedule keeps the same modality rules

    @Test
    void aRescheduleFollowsTheSameModalityRulesAndStaysInsideTheCycle() {
        var semi = cycle(Modality.SEMI_PERSONALIZED);
        var fullCycle = new CycleSnapshot(true, CYCLE_END, 8, 5, 3, Modality.SEMI_PERSONALIZED);   // no quota left, yet moving is fine

        assertThatCode(() -> decide(request(AT, fullCycle, true, false, List.of(), List.of(event(Modality.SEMI_PERSONALIZED, 4, 2)), null))).doesNotThrowAnyException();
        assertThat(codeOf(request(AT, fullCycle, true, false, List.of(), List.of(event(Modality.PERSONALIZED, 1, 1)), null))).isEqualTo(Code.MODALITY_MISMATCH);
        assertThat(codeOf(request(AT, semi, true, false, List.of(), List.of(event(Modality.SEMI_PERSONALIZED, 4, 4)), null))).isEqualTo(Code.EVENT_FULL);
        assertThat(codeOf(request("2026-11-09T10:00", fullCycle, true, false, List.of(), List.of(), null))).isEqualTo(Code.OUTSIDE_CYCLE);
    }
}
