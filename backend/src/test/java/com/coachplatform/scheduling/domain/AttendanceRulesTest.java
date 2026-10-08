package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.domain.SchedulingTestSupport.clockAt;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.codeOf;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.local;
import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.scheduling.api.SessionStatus;
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
        var attended = at("2026-10-12T15:00").mark(SessionStatus.SCHEDULED, SessionStatus.ATTENDED, CLASS_AT, true);
        assertThat(attended.newStatus()).isEqualTo(SessionStatus.ATTENDED);
        assertThat(attended.consumesClass()).isTrue();

        var noShow = at("2026-10-12T15:00").mark(SessionStatus.SCHEDULED, SessionStatus.NO_SHOW, CLASS_AT, true);
        assertThat(noShow.newStatus()).isEqualTo(SessionStatus.NO_SHOW);
        assertThat(noShow.consumesClass()).isTrue();
    }

    @Test
    void aClassCanBeMarkedFromItsStartTimeButNotBefore() {
        assertThat(at("2026-10-12T14:00").mark(SessionStatus.SCHEDULED, SessionStatus.ATTENDED, CLASS_AT, true).consumesClass()).isTrue();
        assertThat(codeOf(() -> at("2026-10-12T13:59:59").mark(SessionStatus.SCHEDULED, SessionStatus.ATTENDED, CLASS_AT, true)))
                .isEqualTo(Code.CLASS_NOT_STARTED);
    }

    @Test
    void theResultCanBeSwitchedWithoutChangingTheCountWhileTheCycleIsActive() {
        var switched = at("2026-10-13T09:00").mark(SessionStatus.NO_SHOW, SessionStatus.ATTENDED, CLASS_AT, true);
        assertThat(switched.newStatus()).isEqualTo(SessionStatus.ATTENDED);
        assertThat(switched.consumesClass()).isFalse();
    }

    @Test
    void markingTheSameResultTwiceIsRejected() {
        assertThat(codeOf(() -> at("2026-10-13T09:00").mark(SessionStatus.ATTENDED, SessionStatus.ATTENDED, CLASS_AT, true)))
                .isEqualTo(Code.ALREADY_MARKED);
    }

    @Test
    void aMarkCanNeverBeUndoneBackToScheduled() {
        assertThat(codeOf(() -> at("2026-10-13T09:00").mark(SessionStatus.ATTENDED, SessionStatus.SCHEDULED, CLASS_AT, true)))
                .isEqualTo(Code.INVALID_STATE);
        assertThat(codeOf(() -> at("2026-10-13T09:00").mark(SessionStatus.NO_SHOW, SessionStatus.CANCELLED_BY_COACH, CLASS_AT, true)))
                .isEqualTo(Code.INVALID_STATE);
    }

    @Test
    void aClosedCycleAcceptsNoNewMarksNorSwitches() {
        assertThat(codeOf(() -> at("2026-10-12T15:00").mark(SessionStatus.SCHEDULED, SessionStatus.ATTENDED, CLASS_AT, false)))
                .isEqualTo(Code.CYCLE_CLOSED);
        assertThat(codeOf(() -> at("2026-10-12T15:00").mark(SessionStatus.NO_SHOW, SessionStatus.ATTENDED, CLASS_AT, false)))
                .isEqualTo(Code.CYCLE_CLOSED);
    }

    @Test
    void cancelledOrRescheduledClassesCannotBeMarked() {
        for (SessionStatus status : new SessionStatus[] {SessionStatus.CANCELLED_ON_TIME, SessionStatus.RESCHEDULED, SessionStatus.CANCELLED_BY_COACH}) {
            assertThat(codeOf(() -> at("2026-10-12T15:00").mark(status, SessionStatus.ATTENDED, CLASS_AT, true)))
                    .as(status.name()).isEqualTo(Code.INVALID_STATE);
        }
    }
}
