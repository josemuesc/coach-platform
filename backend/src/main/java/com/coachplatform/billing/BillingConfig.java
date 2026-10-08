package com.coachplatform.billing;

import com.coachplatform.billing.domain.CycleCalendar;
import com.coachplatform.billing.domain.CycleRules;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Wires the Spring-free domain. "Today" is always the America/Bogota business day. */
@Configuration
@EnableScheduling
class BillingConfig {

    @Bean
    CycleCalendar cycleCalendar(Clock clock) {
        return new CycleCalendar(clock, CycleCalendar.BOGOTA);
    }

    @Bean
    CycleRules cycleRules(CycleCalendar calendar) {
        return new CycleRules(calendar);
    }
}
