package com.coachplatform.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AttemptLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-06T17:00:00Z"));
    private final AttemptLimiter limiter = new AttemptLimiter(clock, 3, Duration.ofMinutes(15));

    @Test
    void blocksAfterMaxFailuresWithinTheWindow() {
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        assertThat(limiter.isBlocked("k")).isFalse();
        limiter.recordFailure("k");
        assertThat(limiter.isBlocked("k")).isTrue();
    }

    @Test
    void failuresOlderThanTheWindowStopCounting() {
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        limiter.recordFailure("k");
        assertThat(limiter.isBlocked("k")).isTrue();

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        assertThat(limiter.isBlocked("k")).isFalse();
    }

    @Test
    void keysAreIndependentAndResetClearsTheCounter() {
        limiter.recordFailure("a");
        limiter.recordFailure("a");
        limiter.recordFailure("a");
        assertThat(limiter.isBlocked("b")).isFalse();

        limiter.reset("a");
        assertThat(limiter.isBlocked("a")).isFalse();
    }

    @Test
    void retryAfterIsTheTimeUntilTheOldestFailureLeavesTheWindow() {
        limiter.recordFailure("k");
        clock.advance(Duration.ofMinutes(5));
        limiter.recordFailure("k");
        limiter.recordFailure("k");

        assertThat(limiter.retryAfter("k")).isEqualTo(Duration.ofMinutes(10));
    }
}
