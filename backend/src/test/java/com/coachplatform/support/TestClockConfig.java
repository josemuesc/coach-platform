package com.coachplatform.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-10-06T17:00:00Z")); // noon in Bogota
    }
}
