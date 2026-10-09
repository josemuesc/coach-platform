package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.domain.SchedulingTestSupport.clockAt;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.codeOf;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.local;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.domain.SchedulingRuleException.Code;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CancellationPolicyTest {

    private static final Instant CLASS_AT = local("2026-10-12T14:00");   // Monday 2:00 pm Bogota

    private static CancellationPolicy at(String now) {
        return new CancellationPolicy(clockAt(now));
    }

    // ---- student: the 2-hour window ----------------------------------------------------------------

    @Test
    void studentMayCancelExactlyTwoHoursBeforeTheClass() {
        assertThatCode(() -> at("2026-10-12T12:00").requireStudentMayCancel(AttendanceStatus.SCHEDULED, CLASS_AT, 2))
                .doesNotThrowAnyException();
    }

    @Test
    void studentMayNotCancelOneSecondInsideTheWindow() {
        assertThat(codeOf(() -> at("2026-10-12T12:00:01").requireStudentMayCancel(AttendanceStatus.SCHEDULED, CLASS_AT, 2)))
                .isEqualTo(Code.CANCELLATION_WINDOW_CLOSED);
        assertThat(codeOf(() -> at("2026-10-12T12:59").requireStudentMayCancel(AttendanceStatus.SCHEDULED, CLASS_AT, 2)))
                .isEqualTo(Code.CANCELLATION_WINDOW_CLOSED);
    }

    @Test
    void studentMayCancelWellInAdvance() {
        assertThatCode(() -> at("2026-10-11T08:00").requireStudentMayCancel(AttendanceStatus.SCHEDULED, CLASS_AT, 2))
                .doesNotThrowAnyException();
    }

    @Test
    void theWindowIsConfigurablePerCoach() {
        // 24 h window: 23 h before is too late, 24 h before is fine, 0 h window allows it until the start
        assertThat(codeOf(() -> at("2026-10-11T15:00").requireStudentMayCancel(AttendanceStatus.SCHEDULED, CLASS_AT, 24)))
                .isEqualTo(Code.CANCELLATION_WINDOW_CLOSED);
        assertThatCode(() -> at("2026-10-11T14:00").requireStudentMayCancel(AttendanceStatus.SCHEDULED, CLASS_AT, 24)).doesNotThrowAnyException();
        assertThatCode(() -> at("2026-10-12T13:59:59").requireStudentMayCancel(AttendanceStatus.SCHEDULED, CLASS_AT, 0)).doesNotThrowAnyException();
    }

    @Test
    void aStudentCannotCancelAClassThatAlreadyStarted() {
        assertThat(codeOf(() -> at("2026-10-12T14:00").requireStudentMayCancel(AttendanceStatus.SCHEDULED, CLASS_AT, 0)))
                .isEqualTo(Code.CLASS_ALREADY_STARTED);
        assertThat(codeOf(() -> at("2026-10-12T15:30").requireStudentMayCancel(AttendanceStatus.SCHEDULED, CLASS_AT, 2)))
                .isEqualTo(Code.CLASS_ALREADY_STARTED);
    }

    @Test
    void onlyAScheduledClassCanBeCancelled() {
        for (AttendanceStatus status : AttendanceStatus.values()) {
            if (status != AttendanceStatus.SCHEDULED) {
                assertThat(codeOf(() -> at("2026-10-10T08:00").requireStudentMayCancel(status, CLASS_AT, 2)))
                        .as(status.name()).isEqualTo(Code.INVALID_STATE);
                assertThat(codeOf(() -> at("2026-10-10T08:00").requireCoachMayCancel(status, "motivo")))
                        .as(status.name()).isEqualTo(Code.INVALID_STATE);
            }
        }
    }

    @Test
    void theFreeWindowCheckUsesTheServerClock() {
        assertThat(at("2026-10-12T12:00").isInsideFreeWindow(CLASS_AT, 2)).isTrue();
        assertThat(at("2026-10-12T12:00:01").isInsideFreeWindow(CLASS_AT, 2)).isFalse();
    }

    // ---- coach: always allowed, reason mandatory ---------------------------------------------------

    @Test
    void theCoachMayCancelAtAnyTimeWithAReason() {
        for (String now : new String[] {"2026-10-05T08:00", "2026-10-12T13:55", "2026-10-12T14:00", "2026-10-12T17:00"}) {
            assertThatCode(() -> at(now).requireCoachMayCancel(AttendanceStatus.SCHEDULED, "Entrenador enfermo"))
                    .as(now).doesNotThrowAnyException();
        }
    }

    @Test
    void theCoachMustGiveAReason() {
        assertThat(codeOf(() -> at("2026-10-12T08:00").requireCoachMayCancel(AttendanceStatus.SCHEDULED, null))).isEqualTo(Code.REASON_REQUIRED);
        assertThat(codeOf(() -> at("2026-10-12T08:00").requireCoachMayCancel(AttendanceStatus.SCHEDULED, "   "))).isEqualTo(Code.REASON_REQUIRED);
    }

    @Test
    void aLateStudentCancellationIsRefusedButTheCoachCanForgiveIt() {
        CancellationPolicy policy = at("2026-10-12T13:00");   // one hour before: inside the window
        assertThat(codeOf(() -> policy.requireStudentMayCancel(AttendanceStatus.SCHEDULED, CLASS_AT, 2)))
                .isEqualTo(Code.CANCELLATION_WINDOW_CLOSED);
        // forgiving = the coach cancels the very same class (no class is deducted), leaving the reason on record
        assertThatCode(() -> policy.requireCoachMayCancel(AttendanceStatus.SCHEDULED, "Perdonada: llovía muy fuerte"))
                .doesNotThrowAnyException();
    }

    // ---- mayStudentCancel: the flag the frontend shows -------------------------------------------------

    @Test
    void mayStudentCancelMatchesTheWindowAndTheStatus() {
        assertThat(at("2026-10-12T12:00").mayStudentCancel(AttendanceStatus.SCHEDULED, CLASS_AT, 2)).isTrue();
        assertThat(at("2026-10-12T12:00:01").mayStudentCancel(AttendanceStatus.SCHEDULED, CLASS_AT, 2)).isFalse();
        assertThat(at("2026-10-12T14:00").mayStudentCancel(AttendanceStatus.SCHEDULED, CLASS_AT, 0)).isFalse();
        assertThat(at("2026-10-12T08:00").mayStudentCancel(AttendanceStatus.ATTENDED, CLASS_AT, 2)).isFalse();
        assertThat(at("2026-10-12T08:00").mayStudentCancel(AttendanceStatus.CANCELLED_BY_COACH, CLASS_AT, 2)).isFalse();
    }
}
