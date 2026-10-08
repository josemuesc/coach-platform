package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.domain.SchedulingTestSupport.BOGOTA;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.clockAt;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.codeOf;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.local;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.coachplatform.scheduling.domain.BookingRules.CycleSnapshot;
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

    private static BookingRules rulesAt(String now) {
        return new BookingRules(clockAt(now), new SlotCalendar(BOGOTA));
    }

    private static CycleSnapshot cycle(int included, int used, int scheduled) {
        return new CycleSnapshot(true, CYCLE_END, included, used, scheduled);
    }

    private static Request request(String startsLocal, CycleSnapshot cycle, boolean byStudent, boolean consumesQuota,
                                   List<Range> blocks, List<Range> booked) {
        return new Request(local(startsLocal), HOUR, cycle, ALL_WEEK, blocks, booked, byStudent, 2, consumesQuota);
    }

    private static Request studentBooking(String startsLocal) {
        return request(startsLocal, cycle(8, 0, 0), true, true, List.of(), List.of());
    }

    private static Range range(String startLocal) {
        Instant s = local(startLocal);
        return new Range(s, s.plus(HOUR));
    }

    @Test
    void aValidBookingReturnsTheClassRange() {
        Range range = rulesAt("2026-10-12T08:00").validate(studentBooking("2026-10-13T10:00"));
        assertThat(range.start()).isEqualTo(local("2026-10-13T10:00"));
        assertThat(range.end()).isEqualTo(local("2026-10-13T11:00"));
    }

    @Test
    void withoutAnActiveCycleNothingCanBeBooked() {
        var inactive = new CycleSnapshot(false, CYCLE_END, 8, 0, 0);
        assertThat(codeOf(() -> rulesAt("2026-10-12T08:00").validate(request("2026-10-13T10:00", inactive, true, true, List.of(), List.of()))))
                .isEqualTo(Code.NO_ACTIVE_CYCLE);
        assertThat(codeOf(() -> rulesAt("2026-10-12T08:00").validate(request("2026-10-13T10:00", null, true, true, List.of(), List.of()))))
                .isEqualTo(Code.NO_ACTIVE_CYCLE);
    }

    @Test
    void aClassInThePastIsRejected() {
        assertThat(codeOf(() -> rulesAt("2026-10-12T08:00").validate(studentBooking("2026-10-12T07:00")))).isEqualTo(Code.CLASS_IN_PAST);
        assertThat(codeOf(() -> rulesAt("2026-10-12T08:00").validate(studentBooking("2026-10-12T08:00")))).isEqualTo(Code.CLASS_IN_PAST);
    }

    // ---- lead time -----------------------------------------------------------------------------

    @Test
    void aStudentMustBookAtLeastTheCancellationWindowAhead() {
        // now 08:00, window 2 h: a 10:00 class is exactly on the limit (allowed), 09:00 is too soon
        assertThatCode(() -> rulesAt("2026-10-12T08:00").validate(studentBooking("2026-10-12T10:00"))).doesNotThrowAnyException();
        assertThat(codeOf(() -> rulesAt("2026-10-12T08:00").validate(studentBooking("2026-10-12T09:00")))).isEqualTo(Code.TOO_SOON);
    }

    @Test
    void theCoachIsExemptFromTheLeadTime() {
        var byCoach = request("2026-10-12T09:00", cycle(8, 0, 0), false, true, List.of(), List.of());
        assertThatCode(() -> rulesAt("2026-10-12T08:00").validate(byCoach)).doesNotThrowAnyException();
    }

    // ---- inside the cycle (reschedule only inside the cycle) -------------------------------------

    @Test
    void theCycleDeadlineDayIsStillBookableEvenInTheEvening() {
        // 7:00 pm Bogota on Nov 6 is already Nov 7 in UTC: the Bogota calendar day is what counts
        assertThatCode(() -> rulesAt("2026-11-01T08:00").validate(studentBooking("2026-11-06T19:00"))).doesNotThrowAnyException();
        assertThat(local("2026-11-06T19:00").toString()).startsWith("2026-11-07T00:00");
    }

    @Test
    void theDayAfterTheDeadlineIsOutsideTheCycle() {
        assertThat(codeOf(() -> rulesAt("2026-11-01T08:00").validate(studentBooking("2026-11-07T06:00")))).isEqualTo(Code.OUTSIDE_CYCLE);
        assertThat(codeOf(() -> rulesAt("2026-11-01T08:00").validate(studentBooking("2026-11-20T10:00")))).isEqualTo(Code.OUTSIDE_CYCLE);
    }

    @Test
    void aRescheduleIsAlsoLimitedToTheCycleAndDoesNotNeedAFreeQuota() {
        // a full cycle (8 used + scheduled): a reschedule keeps the same count, so it passes...
        var fullCycle = cycle(8, 5, 3);
        var reschedule = request("2026-11-05T10:00", fullCycle, true, false, List.of(), List.of());
        assertThatCode(() -> rulesAt("2026-11-01T08:00").validate(reschedule)).doesNotThrowAnyException();
        // ...but only inside the cycle
        var outside = request("2026-11-09T10:00", fullCycle, true, false, List.of(), List.of());
        assertThat(codeOf(() -> rulesAt("2026-11-01T08:00").validate(outside))).isEqualTo(Code.OUTSIDE_CYCLE);
    }

    // ---- availability, blocks, overlap -------------------------------------------------------------

    @Test
    void aTimeOutsideTheWeeklyWindowsOrOffTheGridIsNotAvailable() {
        assertThat(codeOf(() -> rulesAt("2026-10-12T08:00").validate(studentBooking("2026-10-13T21:00")))).isEqualTo(Code.NOT_AVAILABLE);
        assertThat(codeOf(() -> rulesAt("2026-10-12T08:00").validate(studentBooking("2026-10-13T10:30")))).isEqualTo(Code.NOT_AVAILABLE);
    }

    @Test
    void aBlockedTimeIsRejected() {
        var blocked = request("2026-10-13T10:00", cycle(8, 0, 0), true, true, List.of(range("2026-10-13T09:30")), List.of());
        assertThat(codeOf(() -> rulesAt("2026-10-12T08:00").validate(blocked))).isEqualTo(Code.BLOCKED);
    }

    @Test
    void aTakenSlotIsRejectedIncludingPartialOverlaps() {
        var identical = request("2026-10-13T10:00", cycle(8, 0, 0), true, true, List.of(), List.of(range("2026-10-13T10:00")));
        assertThat(codeOf(() -> rulesAt("2026-10-12T08:00").validate(identical))).isEqualTo(Code.SLOT_TAKEN);

        // an existing class of another length (booked under an older duration) that overlaps the new slot
        var longClass = new Range(local("2026-10-13T09:30"), local("2026-10-13T10:30"));
        var partial = request("2026-10-13T10:00", cycle(8, 0, 0), true, true, List.of(), List.of(longClass));
        assertThat(codeOf(() -> rulesAt("2026-10-12T08:00").validate(partial))).isEqualTo(Code.SLOT_TAKEN);

        var backToBack = request("2026-10-13T10:00", cycle(8, 0, 0), true, true, List.of(), List.of(range("2026-10-13T09:00")));
        assertThatCode(() -> rulesAt("2026-10-12T08:00").validate(backToBack)).doesNotThrowAnyException();
    }

    // ---- quota: used + scheduled <= included ---------------------------------------------------------

    @Test
    void usedPlusScheduledCanNeverExceedWhatTheCycleIncludes() {
        // 8 included: 5 used + 2 scheduled = 7, one more fits; 5 + 3 = 8, none fits
        assertThatCode(() -> rulesAt("2026-10-12T08:00").validate(
                request("2026-10-13T10:00", cycle(8, 5, 2), true, true, List.of(), List.of()))).doesNotThrowAnyException();
        assertThat(codeOf(() -> rulesAt("2026-10-12T08:00").validate(
                request("2026-10-13T10:00", cycle(8, 5, 3), true, true, List.of(), List.of())))).isEqualTo(Code.QUOTA_EXCEEDED);
        assertThat(codeOf(() -> rulesAt("2026-10-12T08:00").validate(
                request("2026-10-13T10:00", cycle(8, 8, 0), true, true, List.of(), List.of())))).isEqualTo(Code.QUOTA_EXCEEDED);
    }

    @Test
    void aCancelledClassFreesItsPlaceInTheQuota() {
        // scheduled count excludes cancelled/rescheduled classes, so 5 used + 2 scheduled leaves room again
        assertThatCode(() -> rulesAt("2026-10-12T08:00").validate(
                request("2026-10-13T10:00", cycle(8, 5, 2), true, true, List.of(), List.of()))).doesNotThrowAnyException();
    }
}
