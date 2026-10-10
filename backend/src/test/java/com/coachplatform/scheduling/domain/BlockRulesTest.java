package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.domain.SchedulingTestSupport.BOGOTA;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.clockAt;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.codeOf;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.local;
import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.domain.BlockRules.Effect;
import com.coachplatform.scheduling.domain.SchedulingRuleException.Code;
import com.coachplatform.scheduling.domain.SlotCalendar.Range;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class BlockRulesTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);
    private final BlockRules rules = new BlockRules(clockAt("2026-10-12T08:00"), BOGOTA);

    @Test
    void aBlockByHoursIsReadInTheCoachsLocalTime() {
        Range r = rules.resolve(DAY, false, LocalTime.of(10, 0), LocalTime.of(12, 0));
        assertThat(r.start()).isEqualTo(local("2026-10-16T10:00"));
        assertThat(r.end()).isEqualTo(local("2026-10-16T12:00"));
        assertThat(rules.isAllDay(r)).isFalse();
    }

    @Test
    void anAllDayBlockCoversTheLocalDayFromMidnightToMidnightNotTheUtcDay() {
        Range r = rules.resolve(DAY, true, null, null);
        assertThat(r.start()).isEqualTo(local("2026-10-16T00:00"));
        assertThat(r.end()).isEqualTo(local("2026-10-17T00:00"));
        assertThat(rules.isAllDay(r)).isTrue();
    }

    @Test
    void theEndMustBeAfterTheStartAndBothMustBeGivenWhenNotAllDay() {
        assertThat(codeOf(() -> rules.resolve(DAY, false, LocalTime.of(12, 0), LocalTime.of(10, 0)))).isEqualTo(Code.INVALID_BLOCK);
        assertThat(codeOf(() -> rules.resolve(DAY, false, LocalTime.of(10, 0), LocalTime.of(10, 0)))).isEqualTo(Code.INVALID_BLOCK);
        assertThat(codeOf(() -> rules.resolve(DAY, false, null, LocalTime.of(10, 0)))).isEqualTo(Code.INVALID_BLOCK);
        assertThat(codeOf(() -> rules.resolve(DAY, false, LocalTime.of(10, 0), null))).isEqualTo(Code.INVALID_BLOCK);
    }

    @Test
    void aBlockThatHasAlreadyEndedIsRefusedButOneStillRunningIsAccepted() {
        var noon = new BlockRules(clockAt("2026-10-16T11:00"), BOGOTA);
        assertThat(codeOf(() -> noon.resolve(DAY, false, LocalTime.of(8, 0), LocalTime.of(10, 0)))).isEqualTo(Code.INVALID_BLOCK);
        assertThat(codeOf(() -> noon.resolve(DAY, false, LocalTime.of(8, 0), LocalTime.of(11, 0)))).as("ends exactly now").isEqualTo(Code.INVALID_BLOCK);
        assertThat(noon.resolve(DAY, false, LocalTime.of(8, 0), LocalTime.of(11, 1)).end()).isEqualTo(local("2026-10-16T11:01"));
    }

    @Test
    void aBookedClassThatHasNotStartedIsReleasedAndNothingElseIsTouched() {
        var now = clockAt("2026-10-16T10:00");
        var r = new BlockRules(now, BOGOTA);
        assertThat(r.effectOn(AttendanceStatus.SCHEDULED, local("2026-10-16T10:01"))).isEqualTo(Effect.RELEASE);
        assertThat(r.effectOn(AttendanceStatus.SCHEDULED, local("2026-10-16T10:00"))).as("starting exactly now has started").isEqualTo(Effect.KEEP_PENDING);
        assertThat(r.effectOn(AttendanceStatus.SCHEDULED, local("2026-10-16T08:00"))).isEqualTo(Effect.KEEP_PENDING);
        assertThat(r.effectOn(AttendanceStatus.ATTENDED, local("2026-10-16T11:00"))).isEqualTo(Effect.KEEP_MARKED);
        assertThat(r.effectOn(AttendanceStatus.NO_SHOW, local("2026-10-16T08:00"))).isEqualTo(Effect.KEEP_MARKED);
        assertThat(r.effectOn(AttendanceStatus.CANCELLED_ON_TIME, local("2026-10-16T11:00"))).isEqualTo(Effect.NONE);
        assertThat(r.effectOn(AttendanceStatus.RESCHEDULED, local("2026-10-16T11:00"))).isEqualTo(Effect.NONE);
        assertThat(r.effectOn(AttendanceStatus.CANCELLED_BY_COACH, local("2026-10-16T11:00"))).isEqualTo(Effect.NONE);
    }
}
