package com.coachplatform.students;

import com.coachplatform.students.domain.ConsentLedger;
import com.coachplatform.students.domain.ConsentRules;
import com.coachplatform.students.domain.GuardianRules;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the Spring-free domain of the students module. "Today" is the America/Bogota calendar day. */
@Configuration
class StudentsConfig {

    @Bean
    GuardianRules guardianRules(Clock clock) {
        return new GuardianRules(clock);
    }

    @Bean
    ConsentRules consentRules() {
        return new ConsentRules();
    }

    @Bean
    ConsentLedger consentLedger() {
        return new ConsentLedger();
    }
}
