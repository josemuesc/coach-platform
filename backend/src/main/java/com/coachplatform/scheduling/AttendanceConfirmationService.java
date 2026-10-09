package com.coachplatform.scheduling;

import com.coachplatform.billing.BillingService;
import com.coachplatform.billing.api.CycleStatus;
import com.coachplatform.billing.api.CycleSummary;
import com.coachplatform.coach.CoachService;
import com.coachplatform.coach.api.SchedulingSettings;
import com.coachplatform.common.ApiException;
import com.coachplatform.scheduling.api.ActorRole;
import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.api.AuditAction;
import com.coachplatform.scheduling.api.AuditEntryView;
import com.coachplatform.scheduling.api.AuditMethod;
import com.coachplatform.scheduling.api.ConfirmQrResult;
import com.coachplatform.scheduling.api.ConfirmResult;
import com.coachplatform.scheduling.api.ConfirmationMethod;
import com.coachplatform.scheduling.api.EventStatus;
import com.coachplatform.scheduling.api.QrView;
import com.coachplatform.scheduling.domain.ConfirmationRules;
import com.coachplatform.scheduling.domain.QrTokenRules;
import com.coachplatform.scheduling.domain.SchedulingRuleException;
import com.coachplatform.security.AttemptLimiter;
import com.coachplatform.students.StudentService;
import com.coachplatform.students.api.StudentSummary;
import com.coachplatform.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The student's confirmation of a class and the coach's rotating code.
 *
 * <p>A confirmation is permanent and never changes the cycle count by itself; the one thing that marks a class is a VALID QR scan of
 * a place still SCHEDULED, which goes through {@link AttendanceMarker} exactly like the coach's marking. Lock order is the global one:
 * the student's row first, then the rest (a scan never needs the calendar nor an event lock: it adds no place to any event).
 * The scanned token is never logged: it only travels in the request body and is not part of any exception message.
 */
@Service
public class AttendanceConfirmationService {

    private final QrTokenRules qr;
    private final ConfirmationRules confirmation;
    private final ClassSessionRepository events;
    private final SessionAttendanceRepository attendances;
    private final AttendanceAuditRepository audits;
    private final AttendanceMarker marker;
    private final AttendanceAuditWriter audit;
    private final SchedulingViews views;
    private final StudentService students;
    private final BillingService billing;
    private final CoachService coaches;
    private final AttemptLimiter scanLimiter;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String frontendUrl;

    AttendanceConfirmationService(QrTokenRules qr, ConfirmationRules confirmation, ClassSessionRepository events,
                                  SessionAttendanceRepository attendances, AttendanceAuditRepository audits, AttendanceMarker marker,
                                  AttendanceAuditWriter audit, SchedulingViews views, StudentService students, BillingService billing,
                                  CoachService coaches, @Qualifier("qrScanUserLimiter") AttemptLimiter scanLimiter, TransactionTemplate tx,
                                  Clock clock, @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.qr = qr;
        this.confirmation = confirmation;
        this.events = events;
        this.attendances = attendances;
        this.audits = audits;
        this.marker = marker;
        this.audit = audit;
        this.views = views;
        this.students = students;
        this.billing = billing;
        this.coaches = coaches;
        this.scanLimiter = scanLimiter;
        this.tx = tx;
        this.clock = clock;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    // ---- the coach shows the code ---------------------------------------------------------------------------

    /** The current code of an event, available from {@code qrOpenMinutesBefore} before it starts until {@code qrCloseHoursAfterEnd} after it ends. */
    @Transactional(readOnly = true)
    public QrView issueQr(UUID eventId) {
        ClassSession event = events.findById(eventId).orElseThrow(EventNotFoundException::new);
        if (event.getStatus() != EventStatus.SCHEDULED) {
            throw new SchedulingRuleException(SchedulingRuleException.Code.INVALID_STATE, "A cancelled class has no code");
        }
        SchedulingSettings settings = coaches.schedulingSettings(TenantContext.get());
        confirmation.requireMayIssue(event.getStartsAt(), event.getEndsAt(), settings.qrOpenMinutesBefore(), settings.qrCloseHoursAfterEnd());
        QrTokenRules.IssuedToken issued = qr.issue(eventId);
        return new QrView(issued.token(), frontendUrl + "/qr#t=" + issued.token(), issued.validForSeconds(), issued.expiresAt(),
                event.getStartsAt().minus(java.time.Duration.ofMinutes(settings.qrOpenMinutesBefore())),
                event.getEndsAt().plus(java.time.Duration.ofHours(settings.qrCloseHoursAfterEnd())));
    }

    // ---- the student scans it ---------------------------------------------------------------------------------

    /**
     * A student scans the event's code. The student is the token's user (never a parameter). A valid scan of a place still
     * SCHEDULED marks it ATTENDED (using up one class) and confirms it; a place the coach already marked only gets the confirmation;
     * a second scan changes nothing. Repeated failures (an invalid, stale or foreign code) are throttled per student.
     */
    public ConfirmQrResult confirmByQr(UUID studentUserId, String token) {
        String key = studentUserId.toString();
        if (scanLimiter.isBlocked(key)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS");
        }
        try {
            return tx.execute(status -> doConfirmByQr(studentUserId, token));
        } catch (RuntimeException e) {
            if (isBadCode(e)) {
                scanLimiter.recordFailure(key);
            }
            throw e;
        }
    }

    private ConfirmQrResult doConfirmByQr(UUID studentUserId, String token) {
        UUID eventId = qr.verify(token);                                                // INVALID_QR for anything but a live, genuine code
        StudentSummary student = students.findByUserId(studentUserId);
        students.lockForUpdate(student.id());                                           // 1. the student (same order as the coach's marking)
        SessionAttendance place = attendances.findLiveBySessionAndStudent(eventId, student.id())     // read under the lock
                .orElseThrow(AttendanceNotFoundException::new);                         // not in that event / a cancelled place: same 404 as any foreign place
        ClassSession event = events.findById(eventId).orElseThrow(EventNotFoundException::new);
        SchedulingSettings settings = coaches.schedulingSettings(TenantContext.get());
        CycleSummary cycle = billing.cycle(place.getCycleId());
        var outcome = confirmation.scan(place.getStatus(), place.isConfirmed(), event.getStartsAt(), event.getEndsAt(),
                cycle.status() == CycleStatus.ACTIVE, settings.qrCloseHoursAfterEnd());
        if (outcome.markAttended()) {
            marker.mark(place, AttendanceStatus.ATTENDED, studentUserId, ActorRole.STUDENT, AuditMethod.QR);
        }
        if (outcome.recordConfirmation()) {
            recordConfirmation(place, ConfirmationMethod.QR, AuditMethod.QR, studentUserId);
        }
        return new ConfirmQrResult(views.attendanceView(place, false), outcome.markAttended(), outcome.alreadyConfirmed());
    }

    private static boolean isBadCode(RuntimeException e) {
        return (e instanceof SchedulingRuleException s && s.code() == SchedulingRuleException.Code.INVALID_QR)
                || e instanceof AttendanceNotFoundException;
    }

    // ---- the student confirms afterwards ----------------------------------------------------------------------

    /** Confirms one of the student's own started classes from their history, within the coach's window. It only records the confirmation. */
    public ConfirmResult confirmLater(UUID studentUserId, UUID attendanceId) {
        return tx.execute(status -> {
            StudentSummary student = students.findByUserId(studentUserId);
            students.lockForUpdate(student.id());                                       // 1. the student
            SessionAttendance place = attendances.findById(attendanceId).orElseThrow(AttendanceNotFoundException::new);   // read under the lock
            if (!place.getStudentId().equals(student.id())) {
                throw new AttendanceNotFoundException();                                // someone else's place: reveal nothing
            }
            ClassSession event = events.findById(place.getSessionId()).orElseThrow(EventNotFoundException::new);
            SchedulingSettings settings = coaches.schedulingSettings(TenantContext.get());
            var outcome = confirmation.confirmLater(place.getStatus(), place.isConfirmed(), event.getStartsAt(), settings.confirmationWindowHours());
            if (outcome.recordConfirmation()) {
                recordConfirmation(place, ConfirmationMethod.LATER, AuditMethod.LATER, studentUserId);
            }
            return new ConfirmResult(views.attendanceView(place, false), outcome.alreadyConfirmed());
        });
    }

    private void recordConfirmation(SessionAttendance place, ConfirmationMethod method, AuditMethod auditMethod, UUID studentUserId) {
        Instant now = clock.instant();
        place.confirm(method, now);
        attendances.saveAndFlush(place);
        audit.record(place, AuditAction.CONFIRM, place.getStatus(), place.getStatus(), auditMethod, studentUserId, ActorRole.STUDENT, null);
    }

    // ---- the coach reads the audit ----------------------------------------------------------------------------

    /** The full, immutable history of one attendance (404 for one of another tenant). */
    @Transactional(readOnly = true)
    public List<AuditEntryView> auditOf(UUID attendanceId) {
        attendances.findById(attendanceId).orElseThrow(AttendanceNotFoundException::new);
        // Lines written in the same instant (one scan marks AND confirms) keep their logical order: the id is random, so it cannot break a tie.
        return audits.findByAttendanceIdOrderByOccurredAtAscIdAsc(attendanceId).stream()
                .sorted(java.util.Comparator.comparing(AttendanceAudit::getOccurredAt).thenComparingInt(a -> rank(a.getAction())))
                .map(a -> new AuditEntryView(a.getAction(), a.getPreviousStatus(), a.getNewStatus(), a.getMethod(), a.getActorRole(),
                        a.getActorUserId(), a.getReason(), a.getOccurredAt())).toList();
    }

    private static int rank(AuditAction action) {
        return switch (action) {
            case BOOK -> 0;
            case RESCHEDULE, CANCEL, MARK, TRANSFER -> 1;
            case CONFIRM -> 2;
        };
    }
}
