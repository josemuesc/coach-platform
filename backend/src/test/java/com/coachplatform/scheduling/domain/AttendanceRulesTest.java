package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.domain.SchedulingTestSupport.clockAt;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.codeOf;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.local;
import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.domain.SchedulingRuleException.Code;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AttendanceRulesTest {

    private static final Instant CLASS_AT = local("2026-10-12T14:00");

    private static AttendanceRules at(String now) {
        return new AttendanceRules(clockAt(now));
    }

    @Test
    void markingAttendedOrNoShowUsesUpOneClass() {
        var attended = at("2026-10-12T15:00").mark(AttendanceStatus.SCHEDULED, AttendanceStatus.ATTENDED, CLASS_AT, true);
        assertThat(attended.newStatus()).isEqualTo(AttendanceStatus.ATTENDED);
        assertThat(attended.consumesClass()).isTrue();

        var noShow = at("2026-10-12T15:00").mark(AttendanceStatus.SCHEDULED, AttendanceStatus.NO_SHOW, CLASS_AT, true);
        assertThat(noShow.newStatus()).isEqualTo(AttendanceStatus.NO_SHOW);
        assertThat(noShow.consumesClass()).isTrue();
    }

    @Test
    void aClassCanBeMarkedFromItsStartTimeButNotBefore() {
        assertThat(at("2026-10-12T14:00").mark(AttendanceStatus.SCHEDULED, AttendanceStatus.ATTENDED, CLASS_AT, true).consumesClass()).isTrue();
        assertThat(codeOf(() -> at("2026-10-12T13:59:59").mark(AttendanceStatus.SCHEDULED, AttendanceStatus.ATTENDED, CLASS_AT, true)))
                .isEqualTo(Code.CLASS_NOT_STARTED);
    }

    @Test
    void theResultCanBeSwitchedWithoutChangingTheCountWhileTheCycleIsActive() {
        var switched = at("2026-10-13T09:00").mark(AttendanceStatus.NO_SHOW, AttendanceStatus.ATTENDED, CLASS_AT, true);
        assertThat(switched.newStatus()).isEqualTo(AttendanceStatus.ATTENDED);
        assertThat(switched.consumesClass()).isFalse();
    }

    @Test
    void markingTheSameResultTwiceIsRejected() {
        assertThat(codeOf(() -> at("2026-10-13T09:00").mark(AttendanceStatus.ATTENDED, AttendanceStatus.ATTENDED, CLASS_AT, true)))
                .isEqualTo(Code.ALREADY_MARKED);
    }

    @Test
    void aMarkCanNeverBeUndoneBackToScheduled() {
        assertThat(codeOf(() -> at("2026-10-13T09:00").mark(AttendanceStatus.ATTENDED, AttendanceStatus.SCHEDULED, CLASS_AT, true)))
                .isEqualTo(Code.INVALID_STATE);
        assertThat(codeOf(() -> at("2026-10-13T09:00").mark(AttendanceStatus.NO_SHOW, AttendanceStatus.CANCELLED_BY_COACH, CLASS_AT, true)))
                .isEqualTo(Code.INVALID_STATE);
    }

    @Test
    void aClosedCycleAcceptsNoNewMarksNorSwitches() {
        assertThat(codeOf(() -> at("2026-10-12T15:00").mark(AttendanceStatus.SCHEDULED, AttendanceStatus.ATTENDED, CLASS_AT, false)))
                .isEqualTo(Code.CYCLE_CLOSED);
        assertThat(codeOf(() -> at("2026-10-12T15:00").mark(AttendanceStatus.NO_SHOW, AttendanceStatus.ATTENDED, CLASS_AT, false)))
                .isEqualTo(Code.CYCLE_CLOSED);
    }

    @Test
    void cancelledOrRescheduledClassesCannotBeMarked() {
        for (AttendanceStatus status : new AttendanceStatus[] {AttendanceStatus.CANCELLED_ON_TIME, AttendanceStatus.RESCHEDULED, AttendanceStatus.CANCELLED_BY_COACH}) {
            assertThat(codeOf(() -> at("2026-10-12T15:00").mark(status, AttendanceStatus.ATTENDED, CLASS_AT, true)))
                    .as(status.name()).isEqualTo(Code.INVALID_STATE);
        }
    }
}
