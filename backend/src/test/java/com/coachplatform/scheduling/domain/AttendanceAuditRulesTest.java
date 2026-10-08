package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.api.ActorRole.COACH;
import static com.coachplatform.scheduling.api.ActorRole.STUDENT;
import static com.coachplatform.scheduling.api.AttendanceStatus.ATTENDED;
import static com.coachplatform.scheduling.api.AttendanceStatus.CANCELLED_BY_COACH;
import static com.coachplatform.scheduling.api.AttendanceStatus.CANCELLED_ON_TIME;
import static com.coachplatform.scheduling.api.AttendanceStatus.NO_SHOW;
import static com.coachplatform.scheduling.api.AttendanceStatus.RESCHEDULED;
import static com.coachplatform.scheduling.api.AttendanceStatus.SCHEDULED;
import static com.coachplatform.scheduling.api.AuditAction.BOOK;
import static com.coachplatform.scheduling.api.AuditAction.CANCEL;
import static com.coachplatform.scheduling.api.AuditAction.CONFIRM;
import static com.coachplatform.scheduling.api.AuditAction.MARK;
import static com.coachplatform.scheduling.api.AuditAction.RESCHEDULE;
import static com.coachplatform.scheduling.api.AuditAction.TRANSFER;
import static com.coachplatform.scheduling.api.AuditMethod.LATER;
import static com.coachplatform.scheduling.api.AuditMethod.QR;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coachplatform.scheduling.api.ActorRole;
import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.api.AuditAction;
import com.coachplatform.scheduling.api.AuditMethod;
import org.junit.jupiter.api.Test;

class AttendanceAuditRulesTest {

    private final AttendanceAuditRules rules = new AttendanceAuditRules();

    private void ok(AuditAction a, AttendanceStatus from, AttendanceStatus to, AuditMethod m, ActorRole who) {
        assertThatCode(() -> rules.validate(a, from, to, m, who, "motivo")).doesNotThrowAnyException();
    }

    private void bad(AuditAction a, AttendanceStatus from, AttendanceStatus to, AuditMethod m, ActorRole who) {
        assertThatThrownBy(() -> rules.validate(a, from, to, m, who, "motivo")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyRealFlowIsAccepted() {
        ok(BOOK, null, SCHEDULED, AuditMethod.COACH, COACH);
        ok(BOOK, null, SCHEDULED, AuditMethod.STUDENT, STUDENT);
        ok(MARK, SCHEDULED, ATTENDED, AuditMethod.COACH, COACH);
        ok(MARK, SCHEDULED, NO_SHOW, AuditMethod.COACH, COACH);
        ok(MARK, NO_SHOW, ATTENDED, AuditMethod.COACH, COACH);
        ok(MARK, SCHEDULED, ATTENDED, QR, STUDENT);
        ok(CANCEL, SCHEDULED, CANCELLED_ON_TIME, AuditMethod.STUDENT, STUDENT);
        ok(CANCEL, SCHEDULED, CANCELLED_BY_COACH, AuditMethod.COACH, COACH);
        ok(RESCHEDULE, SCHEDULED, RESCHEDULED, AuditMethod.STUDENT, STUDENT);
        ok(CONFIRM, ATTENDED, ATTENDED, QR, STUDENT);
        ok(CONFIRM, SCHEDULED, SCHEDULED, LATER, STUDENT);
        ok(CONFIRM, NO_SHOW, NO_SHOW, LATER, STUDENT);
    }

    @Test
    void aTransferIsTheCoachMovingAScheduledPlaceWithAReason() {
        ok(TRANSFER, SCHEDULED, SCHEDULED, AuditMethod.COACH, COACH);
        bad(TRANSFER, SCHEDULED, SCHEDULED, AuditMethod.STUDENT, STUDENT);
        bad(TRANSFER, SCHEDULED, SCHEDULED, AuditMethod.STUDENT, COACH);
        bad(TRANSFER, SCHEDULED, ATTENDED, AuditMethod.COACH, COACH);
        bad(TRANSFER, ATTENDED, ATTENDED, AuditMethod.COACH, COACH);
        bad(TRANSFER, null, SCHEDULED, AuditMethod.COACH, COACH);
    }

    @Test
    void aTransferAndACoachCancellationNeedAReason() {
        for (String blank : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> rules.validate(TRANSFER, SCHEDULED, SCHEDULED, AuditMethod.COACH, COACH, blank))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> rules.validate(CANCEL, SCHEDULED, CANCELLED_BY_COACH, AuditMethod.COACH, COACH, blank))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatCode(() -> rules.validate(CANCEL, SCHEDULED, CANCELLED_ON_TIME, AuditMethod.STUDENT, STUDENT, null)).doesNotThrowAnyException();
        assertThatCode(() -> rules.validate(BOOK, null, SCHEDULED, AuditMethod.COACH, COACH, null)).doesNotThrowAnyException();
    }

    @Test
    void bookMustStartFromNothingAndEndScheduled() {
        bad(BOOK, SCHEDULED, SCHEDULED, AuditMethod.COACH, COACH);
        bad(BOOK, null, ATTENDED, AuditMethod.COACH, COACH);
    }

    @Test
    void methodMustMatchTheActor() {
        bad(BOOK, null, SCHEDULED, AuditMethod.STUDENT, COACH);
        bad(CANCEL, SCHEDULED, CANCELLED_BY_COACH, AuditMethod.STUDENT, COACH);
    }

    @Test
    void aStudentCanOnlyMarkByScanningAndOnlyAttended() {
        bad(MARK, SCHEDULED, ATTENDED, AuditMethod.STUDENT, STUDENT);
        bad(MARK, SCHEDULED, NO_SHOW, QR, STUDENT);
        bad(MARK, NO_SHOW, ATTENDED, QR, STUDENT);       // a scan never flips a coach's mark
        bad(MARK, SCHEDULED, ATTENDED, QR, COACH);
    }

    @Test
    void aMarkMustChangeTheStatusAndTargetAMarkedState() {
        bad(MARK, ATTENDED, ATTENDED, AuditMethod.COACH, COACH);
        bad(MARK, SCHEDULED, CANCELLED_ON_TIME, AuditMethod.COACH, COACH);
        bad(MARK, CANCELLED_BY_COACH, ATTENDED, AuditMethod.COACH, COACH);
    }

    @Test
    void onlyTheRightPartyProducesEachCancellationState() {
        bad(CANCEL, SCHEDULED, CANCELLED_ON_TIME, AuditMethod.COACH, COACH);
        bad(CANCEL, SCHEDULED, CANCELLED_BY_COACH, AuditMethod.STUDENT, STUDENT);
        bad(CANCEL, ATTENDED, CANCELLED_BY_COACH, AuditMethod.COACH, COACH);
        bad(CANCEL, SCHEDULED, RESCHEDULED, AuditMethod.STUDENT, STUDENT);
    }

    @Test
    void rescheduleIsAStudentActionToRescheduled() {
        bad(RESCHEDULE, SCHEDULED, RESCHEDULED, AuditMethod.COACH, COACH);
        bad(RESCHEDULE, SCHEDULED, CANCELLED_ON_TIME, AuditMethod.STUDENT, STUDENT);
    }

    @Test
    void aConfirmationNeverChangesTheStatusAndIsAlwaysTheStudents() {
        bad(CONFIRM, SCHEDULED, ATTENDED, QR, STUDENT);
        bad(CONFIRM, ATTENDED, ATTENDED, AuditMethod.COACH, COACH);
        bad(CONFIRM, ATTENDED, ATTENDED, AuditMethod.STUDENT, STUDENT);
        bad(CONFIRM, CANCELLED_ON_TIME, CANCELLED_ON_TIME, LATER, STUDENT);
        bad(CONFIRM, null, null, LATER, STUDENT);
    }
}
