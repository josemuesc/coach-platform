package com.coachplatform.scheduling;

import com.coachplatform.scheduling.domain.AttendanceRules;
import com.coachplatform.scheduling.domain.BookingRules;
import com.coachplatform.scheduling.domain.CancellationPolicy;
import com.coachplatform.scheduling.domain.SlotCalendar;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the Spring-free domain. The business day is always America/Bogota. */
@Configuration
class SchedulingConfig {

    static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    @Bean
    SlotCalendar slotCalendar() {
        return new SlotCalendar(BOGOTA);
    }

    @Bean
    BookingRules bookingRules(Clock clock, SlotCalendar slots) {
        return new BookingRules(clock, slots);
    }

    @Bean
    CancellationPolicy cancellationPolicy(Clock clock) {
        return new CancellationPolicy(clock);
    }

    @Bean
    AttendanceRules attendanceRules(Clock clock) {
        return new AttendanceRules(clock);
    }
}
