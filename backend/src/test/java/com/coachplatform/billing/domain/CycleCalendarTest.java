package com.coachplatform.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CycleCalendarTest {

    private static CycleCalendar at(String instant) {
        return new CycleCalendar(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), CycleCalendar.BOGOTA);
    }

    @ParameterizedTest(name = "paid {0} -> ends {1}")
    @CsvSource({
            "2026-10-06, 2026-11-06",   // the example from the product spec
            "2026-01-29, 2026-02-28",   // non leap year
            "2026-01-30, 2026-02-28",
            "2026-01-31, 2026-02-28",
            "2028-01-29, 2028-02-29",   // leap year
            "2028-01-30, 2028-02-29",
            "2028-01-31, 2028-02-29",
            "2026-03-31, 2026-04-30",   // 31 -> 30-day month
            "2026-08-31, 2026-09-30",
            "2026-12-31, 2027-01-31",
            "2026-02-28, 2026-03-28",
            "2028-02-29, 2028-03-29"
    })
    void endDateIsPaymentDatePlusOneMonth(LocalDate paidOn, LocalDate expectedEnd) {
        assertThat(at("2026-10-06T15:00:00Z").endDateFor(paidOn)).isEqualTo(expectedEnd);
    }

    @Test
    void todayIsTheBogotaDateNotTheUtcDate() {
        // 03:00 UTC on Oct 7 is 22:00 on Oct 6 in Bogota (UTC-5, no DST).
        assertThat(at("2026-10-07T03:00:00Z").today()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(at("2026-10-07T05:00:00Z").today()).isEqualTo(LocalDate.of(2026, 10, 7));
    }
}
