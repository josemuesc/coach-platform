package com.coachplatform.coach.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.coach.domain.GymConsentRules.State;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GymConsentRulesTest {

    private static final Instant T1 = Instant.parse("2026-10-08T15:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-20T15:00:00Z");
    private final GymConsentRules rules = new GymConsentRules();

    @Test
    void turningItOnStampsTheServersDate() {
        assertThat(rules.apply(new State(false, null), true, T1)).isEqualTo(new State(true, T1));
    }

    @Test
    void keepingItOnKeepsTheOriginalDate() {
        assertThat(rules.apply(new State(true, T1), true, T2)).isEqualTo(new State(true, T1));
    }

    @Test
    void turningItOffClearsTheDate() {
        assertThat(rules.apply(new State(true, T1), false, T2)).isEqualTo(new State(false, null));
        assertThat(rules.apply(new State(false, null), false, T2)).isEqualTo(new State(false, null));
    }

    @Test
    void turningItBackOnGetsANewDate() {
        State off = rules.apply(new State(true, T1), false, T2);
        assertThat(rules.apply(off, true, T2)).isEqualTo(new State(true, T2));
    }
}
