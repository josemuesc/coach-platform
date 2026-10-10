package com.coachplatform.scheduling;

import com.coachplatform.scheduling.api.BlockSummary;
import com.coachplatform.scheduling.domain.BlockRules;
import com.coachplatform.scheduling.domain.SlotCalendar;
import com.coachplatform.scheduling.domain.SlotCalendar.Range;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/** A block as the coach reads it: in local time, with all-day decided here, never by the client. */
@Component
class BlockViews {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final BlockRules rules;
    private final SlotCalendar calendar;

    BlockViews(BlockRules rules, SlotCalendar calendar) {
        this.rules = rules;
        this.calendar = calendar;
    }

    BlockSummary summary(AvailabilityBlock b) {
        ZonedDateTime start = b.getStartsAt().atZone(calendar.zone());
        ZonedDateTime end = b.getEndsAt().atZone(calendar.zone());
        boolean allDay = rules.isAllDay(new Range(b.getStartsAt(), b.getEndsAt()));
        return new BlockSummary(b.getId(), b.getStartsAt(), b.getEndsAt(), start.toLocalDate().toString(), allDay,
                allDay ? null : start.format(HH_MM), allDay ? null : end.format(HH_MM), b.getReason());
    }
}
