package com.coachplatform.billing.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Date arithmetic of cycles. "Today" is always the business day in America/Bogota, never the UTC date. */
public final class CycleCalendar {

    public static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private final Clock clock;
    private final ZoneId zone;

    public CycleCalendar(Clock clock, ZoneId zone) {
        this.clock = clock;
        this.zone = zone;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    public Instant now() {
        return clock.instant();
    }

    public LocalDate toLocalDate(Instant instant) {
        return LocalDate.ofInstant(instant, zone);
    }

    /** Payment date + 1 month. Days 29/30/31 are clamped to the last day of a shorter month. */
    public LocalDate endDateFor(LocalDate start) {
        return start.plusMonths(1);
    }
}
