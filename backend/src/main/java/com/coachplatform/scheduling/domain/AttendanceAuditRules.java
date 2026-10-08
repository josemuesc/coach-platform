package com.coachplatform.scheduling.domain;

import com.coachplatform.scheduling.api.ActorRole;
import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.api.AuditAction;
import com.coachplatform.scheduling.api.AuditMethod;

/**
 * Which audit entries can exist. Every change to an attendance is recorded with who did it and how; a combination that no
 * business flow produces is a programming error, so it throws {@link IllegalArgumentException} (it is never a client error)
 * and nothing is written. The database repeats the same shape in a CHECK constraint.
 */
public final class AttendanceAuditRules {

    public void validate(AuditAction action, AttendanceStatus previous, AttendanceStatus next, AuditMethod method, ActorRole actor,
                         String reason) {
        switch (action) {
            case BOOK -> {
                require(previous == null && next == AttendanceStatus.SCHEDULED, action, "starts from nothing and becomes SCHEDULED");
                requireOwnMethod(action, method, actor);
            }
            case MARK -> {
                require(previous != null && previous.isLive() && previous != next
                        && (next == AttendanceStatus.ATTENDED || next == AttendanceStatus.NO_SHOW), action,
                        "moves a live class to ATTENDED or NO_SHOW, changing its status");
                // the coach marks by hand; a student can only mark by scanning the code
                require((actor == ActorRole.COACH && method == AuditMethod.COACH)
                        || (actor == ActorRole.STUDENT && method == AuditMethod.QR && next == AttendanceStatus.ATTENDED
                        && previous == AttendanceStatus.SCHEDULED), action, "has an impossible actor/method");
            }
            case CANCEL -> {
                require(previous == AttendanceStatus.SCHEDULED
                        && (next == AttendanceStatus.CANCELLED_ON_TIME || next == AttendanceStatus.CANCELLED_BY_COACH), action,
                        "cancels a SCHEDULED class");
                requireOwnMethod(action, method, actor);
                require((next == AttendanceStatus.CANCELLED_BY_COACH) == (actor == ActorRole.COACH), action,
                        "CANCELLED_BY_COACH is only done by the coach and CANCELLED_ON_TIME only by the student");
                require(next != AttendanceStatus.CANCELLED_BY_COACH || hasText(reason), action, "by the coach needs a reason");
            }
            case RESCHEDULE -> {
                require(previous == AttendanceStatus.SCHEDULED && next == AttendanceStatus.RESCHEDULED, action,
                        "moves a SCHEDULED class to RESCHEDULED");
                require(actor == ActorRole.STUDENT && method == AuditMethod.STUDENT, action, "is only done by the student");
            }
            case TRANSFER -> {
                // the coach who records a renewal payment moves a still-scheduled place to the new cycle: no status change
                require(previous == AttendanceStatus.SCHEDULED && next == AttendanceStatus.SCHEDULED, action,
                        "moves a SCHEDULED class to the new cycle without changing its status");
                require(actor == ActorRole.COACH && method == AuditMethod.COACH, action, "is only done by the coach");
                require(hasText(reason), action, "needs a reason");
            }
            case CONFIRM -> {
                require(previous != null && previous.isLive() && previous == next, action, "does not change the status");
                require(actor == ActorRole.STUDENT && (method == AuditMethod.QR || method == AuditMethod.LATER), action,
                        "is done by the student, by QR or later");
            }
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void requireOwnMethod(AuditAction action, AuditMethod method, ActorRole actor) {
        require((actor == ActorRole.COACH && method == AuditMethod.COACH) || (actor == ActorRole.STUDENT && method == AuditMethod.STUDENT),
                action, "must carry the method of its actor");
    }

    private static void require(boolean condition, AuditAction action, String what) {
        if (!condition) {
            throw new IllegalArgumentException("An audit entry of type " + action + " " + what);
        }
    }
}
