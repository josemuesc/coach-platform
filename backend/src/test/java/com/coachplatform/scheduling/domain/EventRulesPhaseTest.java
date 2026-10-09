package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.domain.SchedulingTestSupport.clockAt;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.local;
import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.scheduling.api.EventPhase;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventRulesPhaseTest {

    private static final Instant START = local("2026-10-12T14:00");
    private static final Instant END = local("2026-10-12T15:00");

    private static EventPhase at(String now) {
        return new EventRules(clockAt(now)).phase(START, END);
    }

    @Test
    void upcomingUntilTheStartInstant() {
        assertThat(at("2026-10-12T13:59:59")).isEqualTo(EventPhase.UPCOMING);
        assertThat(at("2026-10-12T14:00")).isEqualTo(EventPhase.NOW);
    }

    @Test
    void nowUntilTheEndInstantAndPastFromThere() {
        assertThat(at("2026-10-12T14:59:59")).isEqualTo(EventPhase.NOW);
        assertThat(at("2026-10-12T15:00")).isEqualTo(EventPhase.PAST);
        assertThat(at("2026-10-13T09:00")).isEqualTo(EventPhase.PAST);
    }
}
