package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.domain.SchedulingTestSupport.clockAt;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.codeOf;
import static com.coachplatform.scheduling.domain.SchedulingTestSupport.local;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.domain.ConfirmationRules.Outcome;
import com.coachplatform.scheduling.domain.SchedulingRuleException.Code;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConfirmationRulesTest {

    private static final Instant CLASS_AT = local("2026-10-12T14:00");
    private static final Instant CLASS_END = local("2026-10-12T15:00");
    private static final int CLOSES = 2;   // hours after the end

    private static ConfirmationRules at(String now) {
        return new ConfirmationRules(clockAt(now));
    }

    // ---- QR scan -----------------------------------------------------------------------------------

    @Test
    void scanningAScheduledClassMarksItAttendedAndConfirms() {
        assertThat(at("2026-10-12T14:00").scan(AttendanceStatus.SCHEDULED, false, CLASS_AT, CLASS_END, true, CLOSES))
                .isEqualTo(new Outcome(true, true, false));
    }

    @Test
    void scanningBeforeTheStartIsRefused() {
        assertThat(codeOf(() -> at("2026-10-12T13:59:59").scan(AttendanceStatus.SCHEDULED, false, CLASS_AT, CLASS_END, true, CLOSES)))
                .isEqualTo(Code.CLASS_NOT_STARTED);
    }

    @Test
    void scanningAClassTheCoachAlreadyMarkedAttendedOnlyAddsTheConfirmation() {
        assertThat(at("2026-10-12T15:00").scan(AttendanceStatus.ATTENDED, false, CLASS_AT, CLASS_END, true, CLOSES))
                .isEqualTo(new Outcome(false, true, false));
    }

    @Test
    void scanningANoShowAddsTheConfirmationButDoesNotFlipTheStatus() {
        assertThat(at("2026-10-12T15:00").scan(AttendanceStatus.NO_SHOW, false, CLASS_AT, CLASS_END, true, CLOSES))
                .isEqualTo(new Outcome(false, true, false));
    }

    @Test
    void aSecondScanIsIdempotent() {
        assertThat(at("2026-10-12T15:00").scan(AttendanceStatus.ATTENDED, true, CLASS_AT, CLASS_END, true, CLOSES))
                .isEqualTo(new Outcome(false, false, true));
        assertThat(at("2026-10-12T15:00").scan(AttendanceStatus.NO_SHOW, true, CLASS_AT, CLASS_END, true, CLOSES))
                .isEqualTo(new Outcome(false, false, true));
    }

    @Test
    void aClassConfirmedLaterButStillScheduledIsMarkedByTheScanWithoutANewConfirmation() {
        assertThat(at("2026-10-12T15:00").scan(AttendanceStatus.SCHEDULED, true, CLASS_AT, CLASS_END, true, CLOSES))
                .isEqualTo(new Outcome(true, false, true));
    }

    @Test
    void cancelledAndRescheduledPlacesCannotBeScanned() {
        for (AttendanceStatus status : new AttendanceStatus[] {AttendanceStatus.CANCELLED_ON_TIME, AttendanceStatus.RESCHEDULED,
                AttendanceStatus.CANCELLED_BY_COACH}) {
            assertThat(codeOf(() -> at("2026-10-12T15:00").scan(status, false, CLASS_AT, CLASS_END, true, CLOSES))).isEqualTo(Code.INVALID_STATE);
        }
    }

    @Test
    void scanningAScheduledClassOfAClosedCycleIsRefused() {
        assertThat(codeOf(() -> at("2026-10-12T15:00").scan(AttendanceStatus.SCHEDULED, false, CLASS_AT, CLASS_END, false, CLOSES)))
                .isEqualTo(Code.CYCLE_CLOSED);
    }

    // ---- the QR access window --------------------------------------------------------------------------

    @Test
    void theScanWorksFromTheExactStartAndNotOneSecondBefore() {
        assertThat(at("2026-10-12T14:00:00").scan(AttendanceStatus.SCHEDULED, false, CLASS_AT, CLASS_END, true, CLOSES).markAttended()).isTrue();
        assertThat(codeOf(() -> at("2026-10-12T13:59:59").scan(AttendanceStatus.SCHEDULED, false, CLASS_AT, CLASS_END, true, CLOSES)))
                .isEqualTo(Code.CLASS_NOT_STARTED);
    }

    @Test
    void theScanWorksUntilTwoHoursAfterTheEndAndNotOneSecondMore() {
        assertThat(at("2026-10-12T17:00:00").scan(AttendanceStatus.SCHEDULED, false, CLASS_AT, CLASS_END, true, CLOSES).markAttended()).isTrue();
        assertThat(codeOf(() -> at("2026-10-12T17:00:01").scan(AttendanceStatus.SCHEDULED, false, CLASS_AT, CLASS_END, true, CLOSES)))
                .isEqualTo(Code.QR_WINDOW_CLOSED);
    }

    @Test
    void theLateLimitFollowsTheSettingAndAlsoCoversAlreadyMarkedClasses() {
        assertThat(codeOf(() -> at("2026-10-12T15:00:01").scan(AttendanceStatus.ATTENDED, false, CLASS_AT, CLASS_END, true, 0)))
                .isEqualTo(Code.QR_WINDOW_CLOSED);
        assertThat(at("2026-10-12T15:00:00").scan(AttendanceStatus.ATTENDED, false, CLASS_AT, CLASS_END, true, 0).recordConfirmation()).isTrue();
        assertThat(codeOf(() -> at("2026-10-12T18:00:01").scan(AttendanceStatus.NO_SHOW, true, CLASS_AT, CLASS_END, true, 3)))
                .isEqualTo(Code.QR_WINDOW_CLOSED);
    }

    @Test
    void theNotStartedMessageSaysWhy() {
        try {
            at("2026-10-12T13:00").scan(AttendanceStatus.SCHEDULED, false, CLASS_AT, CLASS_END, true, CLOSES);
            throw new AssertionError("expected CLASS_NOT_STARTED");
        } catch (SchedulingRuleException e) {
            assertThat(e.getMessage()).contains("has not started").contains("once it begins");
        }
    }

    @Test
    void theCoachGetsTheCodeFromFifteenMinutesBeforeTheStartUntilTwoHoursAfterTheEnd() {
        assertThatCode(() -> at("2026-10-12T13:45:00").requireMayIssue(CLASS_AT, CLASS_END, 15, CLOSES)).doesNotThrowAnyException();
        assertThat(codeOf(() -> at("2026-10-12T13:44:59").requireMayIssue(CLASS_AT, CLASS_END, 15, CLOSES))).isEqualTo(Code.QR_NOT_OPEN_YET);
        assertThatCode(() -> at("2026-10-12T17:00:00").requireMayIssue(CLASS_AT, CLASS_END, 15, CLOSES)).doesNotThrowAnyException();
        assertThat(codeOf(() -> at("2026-10-12T17:00:01").requireMayIssue(CLASS_AT, CLASS_END, 15, CLOSES))).isEqualTo(Code.QR_WINDOW_CLOSED);
    }

    @Test
    void theIssueWindowFollowsTheSettings() {
        assertThat(codeOf(() -> at("2026-10-12T13:54:59").requireMayIssue(CLASS_AT, CLASS_END, 5, CLOSES))).isEqualTo(Code.QR_NOT_OPEN_YET);
        assertThatCode(() -> at("2026-10-12T13:55:00").requireMayIssue(CLASS_AT, CLASS_END, 5, CLOSES)).doesNotThrowAnyException();
        assertThatCode(() -> at("2026-10-12T14:00:00").requireMayIssue(CLASS_AT, CLASS_END, 0, CLOSES)).doesNotThrowAnyException();
        assertThat(codeOf(() -> at("2026-10-12T15:00:01").requireMayIssue(CLASS_AT, CLASS_END, 15, 0))).isEqualTo(Code.QR_WINDOW_CLOSED);
    }

    @Test
    void theCodeCanBeShownBeforeTheStartButNotScannedUntilItBegins() {
        // 10 minutes before: the coach may display it, a student scanning it still gets CLASS_NOT_STARTED
        assertThatCode(() -> at("2026-10-12T13:50").requireMayIssue(CLASS_AT, CLASS_END, 15, CLOSES)).doesNotThrowAnyException();
        assertThat(codeOf(() -> at("2026-10-12T13:50").scan(AttendanceStatus.SCHEDULED, false, CLASS_AT, CLASS_END, true, CLOSES)))
                .isEqualTo(Code.CLASS_NOT_STARTED);
    }

    // ---- confirming afterwards ---------------------------------------------------------------------

    @Test
    void laterConfirmationOnlyRecordsItForAnyLiveStatus() {
        for (AttendanceStatus status : new AttendanceStatus[] {AttendanceStatus.SCHEDULED, AttendanceStatus.ATTENDED, AttendanceStatus.NO_SHOW}) {
            assertThat(at("2026-10-12T20:00").confirmLater(status, false, CLASS_AT, 72)).isEqualTo(new Outcome(false, true, false));
        }
    }

    @Test
    void laterConfirmationNeverMarksAnything() {
        assertThat(at("2026-10-12T20:00").confirmLater(AttendanceStatus.SCHEDULED, false, CLASS_AT, 72).markAttended()).isFalse();
    }

    @Test
    void laterConfirmationNeedsTheClassToHaveStarted() {
        assertThat(codeOf(() -> at("2026-10-12T13:59").confirmLater(AttendanceStatus.SCHEDULED, false, CLASS_AT, 72)))
                .isEqualTo(Code.CLASS_NOT_STARTED);
    }

    @Test
    void theWindowIsCountedFromTheStartAndTheExactLimitStillWorks() {
        assertThat(at("2026-10-15T14:00").confirmLater(AttendanceStatus.ATTENDED, false, CLASS_AT, 72).recordConfirmation()).isTrue();
        assertThat(codeOf(() -> at("2026-10-15T14:00:01").confirmLater(AttendanceStatus.ATTENDED, false, CLASS_AT, 72)))
                .isEqualTo(Code.CONFIRMATION_WINDOW_CLOSED);
    }

    @Test
    void theWindowFollowsTheCoachSetting() {
        assertThat(codeOf(() -> at("2026-10-12T16:00:01").confirmLater(AttendanceStatus.ATTENDED, false, CLASS_AT, 2)))
                .isEqualTo(Code.CONFIRMATION_WINDOW_CLOSED);
        assertThat(at("2026-10-12T16:00").confirmLater(AttendanceStatus.ATTENDED, false, CLASS_AT, 2).recordConfirmation()).isTrue();
    }

    @Test
    void confirmingTwiceIsIdempotentEvenAfterTheWindow() {
        assertThat(at("2026-12-01T10:00").confirmLater(AttendanceStatus.ATTENDED, true, CLASS_AT, 72))
                .isEqualTo(new Outcome(false, false, true));
    }

    @Test
    void cancelledPlacesCannotBeConfirmedLater() {
        assertThat(codeOf(() -> at("2026-10-12T20:00").confirmLater(AttendanceStatus.CANCELLED_ON_TIME, false, CLASS_AT, 72)))
                .isEqualTo(Code.INVALID_STATE);
    }

    @Test
    void theConfirmButtonIsOfferedOnlyForAStartedLiveUnconfirmedClassInsideTheWindow() {
        assertThat(at("2026-10-12T14:00").canConfirmLater(AttendanceStatus.ATTENDED, false, CLASS_AT, 72)).isTrue();
        assertThat(at("2026-10-12T13:59:59").canConfirmLater(AttendanceStatus.ATTENDED, false, CLASS_AT, 72)).isFalse();
        assertThat(at("2026-10-15T14:00").canConfirmLater(AttendanceStatus.SCHEDULED, false, CLASS_AT, 72)).isTrue();
        assertThat(at("2026-10-15T14:00:01").canConfirmLater(AttendanceStatus.SCHEDULED, false, CLASS_AT, 72)).isFalse();
        assertThat(at("2026-10-12T20:00").canConfirmLater(AttendanceStatus.ATTENDED, true, CLASS_AT, 72)).isFalse();
        assertThat(at("2026-10-12T20:00").canConfirmLater(AttendanceStatus.CANCELLED_ON_TIME, false, CLASS_AT, 72)).isFalse();
    }

    // ---- what the coach sees -------------------------------------------------------------------------

    @Test
    void aMarkWithoutTheStudentsConfirmationIsShownAsOnlyMarkedByTheCoach() {
        assertThat(ConfirmationRules.isOnlyMarkedByCoach(AttendanceStatus.ATTENDED, false)).isTrue();
        assertThat(ConfirmationRules.isOnlyMarkedByCoach(AttendanceStatus.NO_SHOW, false)).isTrue();
        assertThat(ConfirmationRules.isOnlyMarkedByCoach(AttendanceStatus.ATTENDED, true)).isFalse();
        assertThat(ConfirmationRules.isOnlyMarkedByCoach(AttendanceStatus.SCHEDULED, false)).isFalse();
        assertThat(ConfirmationRules.isOnlyMarkedByCoach(AttendanceStatus.CANCELLED_BY_COACH, false)).isFalse();
    }

    // ---- mayIssue: the flag the frontend shows ---------------------------------------------------------

    @Test
    void mayIssueFollowsTheWindowToTheSecond() {
        Instant start = local("2026-10-12T14:00");
        Instant end = local("2026-10-12T15:00");
        assertThat(new ConfirmationRules(clockAt("2026-10-12T13:44:59")).mayIssue(start, end, 15, 2)).isFalse();
        assertThat(new ConfirmationRules(clockAt("2026-10-12T13:45")).mayIssue(start, end, 15, 2)).isTrue();
        assertThat(new ConfirmationRules(clockAt("2026-10-12T17:00")).mayIssue(start, end, 15, 2)).isTrue();
        assertThat(new ConfirmationRules(clockAt("2026-10-12T17:00:01")).mayIssue(start, end, 15, 2)).isFalse();
    }
}
