package com.coachplatform.scheduling.domain;

import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.domain.SchedulingRuleException.Code;
import com.coachplatform.scheduling.domain.SlotCalendar.Range;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * A block of the coach's calendar, in LOCAL time (the client never computes instants), and what it does to the classes already
 * booked inside it: the ones that have not started are RELEASED (cancelled by the coach, nobody loses a class); a class that
 * was already marked, or that started and still waits to be marked, is history and is never touched.
 */
public final class BlockRules {

    /** What a block does to one booked place. */
    public enum Effect {
        /** SCHEDULED and its event has not started: cancelled without discount. */
        RELEASE,
        /** Already attended / no-show: history, untouched. */
        KEEP_MARKED,
        /** SCHEDULED but the event already started: it waits for the coach's mark, untouched. */
        KEEP_PENDING,
        /** Cancelled or rescheduled already: not a place any more. */
        NONE
    }

    private final Clock clock;
    private final ZoneId zone;

    public BlockRules(Clock clock, ZoneId zone) {
        this.clock = clock;
        this.zone = zone;
    }

    /**
     * The range of a block. allDay = the whole local day; otherwise start and end are wall-clock times of that day (end after start).
     * A block that has already ended is refused: it would block nothing.
     */
    public Range resolve(LocalDate date, boolean allDay, LocalTime start, LocalTime end) {
        Range range;
        if (allDay) {
            range = new Range(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant());
        } else {
            if (start == null || end == null || !end.isAfter(start)) {
                throw new SchedulingRuleException(Code.INVALID_BLOCK, "The block must end after it starts");
            }
            range = new Range(date.atTime(start).atZone(zone).toInstant(), date.atTime(end).atZone(zone).toInstant());
        }
        if (!range.end().isAfter(clock.instant())) {
            throw new SchedulingRuleException(Code.INVALID_BLOCK, "The block is already in the past");
        }
        return range;
    }

    /** True when the range is exactly one or more whole local days (how an "all day" block is read back). */
    public boolean isAllDay(Range range) {
        return range.start().atZone(zone).toLocalTime().equals(LocalTime.MIDNIGHT)
                && range.end().atZone(zone).toLocalTime().equals(LocalTime.MIDNIGHT);
    }

    public Effect effectOn(AttendanceStatus status, Instant eventStartsAt) {
        return switch (status) {
            case SCHEDULED -> eventStartsAt.isAfter(clock.instant()) ? Effect.RELEASE : Effect.KEEP_PENDING;
            case ATTENDED, NO_SHOW -> Effect.KEEP_MARKED;
            default -> Effect.NONE;
        };
    }
}
