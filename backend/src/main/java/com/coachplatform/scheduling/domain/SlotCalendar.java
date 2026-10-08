package com.coachplatform.scheduling.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Cuts the coach's weekly windows into class slots. Windows are local wall-clock times in the coach's zone; slots start
 * at the window start and repeat every class duration, and the last slot must END by the window end.
 */
public final class SlotCalendar {

    public record Window(DayOfWeek day, LocalTime start, LocalTime end) {
        public Window {
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("window end must be after its start");
            }
        }
    }

    /** Half-open range [start, end): back-to-back ranges do not overlap. */
    public record Range(Instant start, Instant end) {
        public Range {
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("range end must be after its start");
            }
        }

        public boolean overlaps(Range other) {
            return start.isBefore(other.end) && other.start.isBefore(end);
        }
    }

    private final ZoneId zone;

    public SlotCalendar(ZoneId zone) {
        this.zone = zone;
    }

    public ZoneId zone() {
        return zone;
    }

    /** Every slot the windows produce on one local date, in time order. */
    public List<Range> slotsOn(LocalDate date, List<Window> windows, Duration duration) {
        List<Range> slots = new ArrayList<>();
        windows.stream().filter(w -> w.day() == date.getDayOfWeek())
                .sorted((a, b) -> a.start().compareTo(b.start()))
                .forEach(w -> {
                    LocalTime cursor = w.start();
                    while (!cursor.plus(duration).isAfter(w.end()) && !cursor.plus(duration).isBefore(cursor)) {
                        ZonedDateTime start = date.atTime(cursor).atZone(zone);
                        slots.add(new Range(start.toInstant(), start.plus(duration).toInstant()));
                        cursor = cursor.plus(duration);
                    }
                });
        return slots;
    }

    /** True if a class starting at {@code startsAt} is exactly one of the slots the windows produce. */
    public boolean isSlotStart(Instant startsAt, List<Window> windows, Duration duration) {
        ZonedDateTime local = startsAt.atZone(zone);
        return slotsOn(local.toLocalDate(), windows, duration).stream().anyMatch(s -> s.start().equals(startsAt));
    }

    /**
     * Free slots for local dates in [from, to] (inclusive): not in the past, not overlapping a block, not overlapping a
     * class that is already booked (whatever its own length, so a duration change never creates a double booking).
     */
    public List<Range> freeSlots(LocalDate from, LocalDate to, List<Window> windows, Duration duration,
                                 List<Range> blocks, List<Range> booked, Instant notBefore) {
        List<Range> free = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (Range slot : slotsOn(date, windows, duration)) {
                boolean past = !slot.start().isAfter(notBefore);
                boolean blocked = blocks.stream().anyMatch(slot::overlaps);
                boolean taken = booked.stream().anyMatch(slot::overlaps);
                if (!past && !blocked && !taken) {
                    free.add(slot);
                }
            }
        }
        return free;
    }
}
