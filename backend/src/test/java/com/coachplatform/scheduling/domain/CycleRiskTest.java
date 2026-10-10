package com.coachplatform.scheduling.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CycleRiskTest {

    @Test
    void atRiskOnlyWhenThereAreClassesToBookAndFewerFreePlacesThanThat() {
        assertThat(CycleRisk.atRisk(1, 0)).isTrue();
        assertThat(CycleRisk.atRisk(3, 2)).isTrue();
        assertThat(CycleRisk.atRisk(3, 3)).isFalse();
        assertThat(CycleRisk.atRisk(1, 5)).isFalse();
        assertThat(CycleRisk.atRisk(0, 0)).as("nothing left to book, nothing at risk").isFalse();
    }
}
