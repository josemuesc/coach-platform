package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.domain.SchedulingTestSupport.BOGOTA;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.local;
import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.scheduling.domain.SlotCalendar.Range;
import com.coachplatform.scheduling.domain.SlotCalendar.Window;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlotCalendarTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);
    private static final SlotCalendar CALENDAR = new SlotCalendar(BOGOTA);
    private static final List<Window> MORNING = List.of(new Window(DayOfWeek.MONDAY, LocalTime.of(6, 0), LocalTime.of(9, 0)));
    private static final Duration HOUR = Duration.ofMinutes(60);

    private static Range range(String startLocal, int minutes) {
        Instant start = local(startLocal);
        return new Range(start, start.plus(Duration.ofMinutes(minutes)));
    }

    private static List<Instant> starts(List<Range> ranges) {
        return ranges.stream().map(Range::start).toList();
    }

    @Test
    void aWindowIsCutIntoSlotsOfTheClassDuration() {
        var slots = CALENDAR.slotsOn(MONDAY, MORNING, HOUR);
        assertThat(starts(slots)).containsExactly(local("2026-10-12T06:00"), local("2026-10-12T07:00"), local("2026-10-12T08:00"));
        assertThat(slots.get(2).end()).isEqualTo(local("2026-10-12T09:00"));   // the last slot ends exactly at the window end
    }

    @Test
    void slotsAreWallClockTimesInBogotaNotUtc() {
        assertThat(CALENDAR.slotsOn(MONDAY, MORNING, HOUR).get(0).start()).isEqualTo(Instant.parse("2026-10-12T11:00:00Z"));
    }

    @Test
    void aSlotThatWouldEndAfterTheWindowIsNotOffered() {
        // 90-minute classes in 06:00-09:00: 06:00-07:30 and 07:30-09:00, nothing else
        assertThat(starts(CALENDAR.slotsOn(MONDAY, MORNING, Duration.ofMinutes(90))))
                .containsExactly(local("2026-10-12T06:00"), local("2026-10-12T07:30"));
        // 2-hour classes: 06:00-08:00 only (08:00-10:00 would overrun)
        assertThat(starts(CALENDAR.slotsOn(MONDAY, MORNING, Duration.ofMinutes(120)))).containsExactly(local("2026-10-12T06:00"));
    }

    @Test
    void onlyWindowsOfThatWeekdayApplyAndSeveralWindowsCombine() {
        var windows = List.of(
                new Window(DayOfWeek.MONDAY, LocalTime.of(6, 0), LocalTime.of(7, 0)),
                new Window(DayOfWeek.MONDAY, LocalTime.of(17, 0), LocalTime.of(19, 0)),
                new Window(DayOfWeek.TUESDAY, LocalTime.of(6, 0), LocalTime.of(7, 0)));
        assertThat(starts(CALENDAR.slotsOn(MONDAY, windows, HOUR)))
                .containsExactly(local("2026-10-12T06:00"), local("2026-10-12T17:00"), local("2026-10-12T18:00"));
        assertThat(CALENDAR.slotsOn(MONDAY.plusDays(2), windows, HOUR)).isEmpty();   // Wednesday
    }

    @Test
    void aStartIsValidOnlyIfItIsExactlyOnTheGrid() {
        assertThat(CALENDAR.isSlotStart(local("2026-10-12T07:00"), MORNING, HOUR)).isTrue();
        assertThat(CALENDAR.isSlotStart(local("2026-10-12T07:30"), MORNING, HOUR)).isFalse();     // between slots
        assertThat(CALENDAR.isSlotStart(local("2026-10-12T09:00"), MORNING, HOUR)).isFalse();     // window end
        assertThat(CALENDAR.isSlotStart(local("2026-10-12T05:00"), MORNING, HOUR)).isFalse();
        assertThat(CALENDAR.isSlotStart(local("2026-10-12T07:00:30"), MORNING, HOUR)).isFalse();  // seconds off
        assertThat(CALENDAR.isSlotStart(local("2026-10-13T07:00"), MORNING, HOUR)).isFalse();     // wrong weekday
    }

    @Test
    void freeSlotsExcludePastBlockedAndBookedSlots() {
        var blocks = List.of(range("2026-10-12T07:00", 60));
        var booked = List.of(range("2026-10-12T08:00", 60));

        var free = CALENDAR.freeSlots(MONDAY, MONDAY, MORNING, HOUR, blocks, booked, local("2026-10-12T05:00"));

        assertThat(starts(free)).containsExactly(local("2026-10-12T06:00"));
    }

    @Test
    void slotsThatAlreadyStartedAreNotOffered() {
        var free = CALENDAR.freeSlots(MONDAY, MONDAY, MORNING, HOUR, List.of(), List.of(), local("2026-10-12T06:30"));
        assertThat(starts(free)).containsExactly(local("2026-10-12T07:00"), local("2026-10-12T08:00"));
    }

    @Test
    void aBlockCoveringPartOfASlotRemovesIt() {
        var blocks = List.of(range("2026-10-12T07:30", 15));   // 07:30-07:45 touches the 07:00 slot
        var free = CALENDAR.freeSlots(MONDAY, MONDAY, MORNING, HOUR, blocks, List.of(), local("2026-10-12T00:00"));
        assertThat(starts(free)).containsExactly(local("2026-10-12T06:00"), local("2026-10-12T08:00"));
    }

    @Test
    void backToBackRangesDoNotOverlap() {
        assertThat(range("2026-10-12T07:00", 60).overlaps(range("2026-10-12T08:00", 60))).isFalse();
        assertThat(range("2026-10-12T07:00", 60).overlaps(range("2026-10-12T07:59", 60))).isTrue();
    }

    @Test
    void changingTheDurationNeverCreatesADoubleBookingWithAnExistingLongerClass() {
        // an existing 60-minute class at 07:00 (booked when the duration was 60); the coach now uses 30-minute classes
        var booked = List.of(range("2026-10-12T07:00", 60));
        var free = CALENDAR.freeSlots(MONDAY, MONDAY, MORNING, Duration.ofMinutes(30), List.of(), booked, local("2026-10-12T00:00"));

        assertThat(starts(free)).containsExactly(
                local("2026-10-12T06:00"), local("2026-10-12T06:30"),   // before the existing class
                local("2026-10-12T08:00"), local("2026-10-12T08:30"));  // after it; 07:00 and 07:30 are covered by it
    }

    @Test
    void freeSlotsSpanSeveralDays() {
        var windows = List.of(new Window(DayOfWeek.MONDAY, LocalTime.of(6, 0), LocalTime.of(7, 0)),
                new Window(DayOfWeek.WEDNESDAY, LocalTime.of(6, 0), LocalTime.of(7, 0)));
        var free = CALENDAR.freeSlots(MONDAY, MONDAY.plusDays(6), windows, HOUR, List.of(), List.of(), local("2026-10-01T00:00"));
        assertThat(starts(free)).containsExactly(local("2026-10-12T06:00"), local("2026-10-14T06:00"));
    }
}
