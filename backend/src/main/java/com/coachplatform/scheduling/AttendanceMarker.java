package com.coachplatform.scheduling;

import com.coachplatform.billing.BillingService;
import com.coachplatform.billing.api.CycleStatus;
import com.coachplatform.billing.api.CycleSummary;
import com.coachplatform.scheduling.api.ActorRole;
import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.api.AuditAction;
import com.coachplatform.scheduling.api.AuditMethod;
import com.coachplatform.scheduling.domain.AttendanceRules;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The ONE way an attendance gets marked ATTENDED / NO_SHOW: used by the coach's marking and by a student's valid QR scan, so both
 * apply the same rules, use up the class the same way and leave the same audit line. The caller already holds the student's row
 * lock (global lock order: the student first) and runs inside a transaction.
 */
@Component
class AttendanceMarker {

    private final ClassSessionRepository events;
    private final SessionAttendanceRepository attendances;
    private final BillingService billing;
    private final AttendanceRules rules;
    private final AttendanceAuditWriter audit;
    private final Clock clock;

    AttendanceMarker(ClassSessionRepository events, SessionAttendanceRepository attendances, BillingService billing,
                     AttendanceRules rules, AttendanceAuditWriter audit, Clock clock) {
        this.events = events;
        this.attendances = attendances;
        this.billing = billing;
        this.rules = rules;
        this.audit = audit;
        this.clock = clock;
    }

    void mark(SessionAttendance place, AttendanceStatus target, UUID actorUserId, ActorRole role, AuditMethod method) {
        ClassSession event = events.findById(place.getSessionId()).orElseThrow(EventNotFoundException::new);
        CycleSummary cycle = billing.cycle(place.getCycleId());
        var outcome = rules.mark(place.getStatus(), target, event.getStartsAt(), cycle.status() == CycleStatus.ACTIVE);
        if (outcome.consumesClass()) {
            billing.consumeClass(place.getCycleId());                                   // before the status changes: it is still "pending"
        }
        AttendanceStatus previous = place.getStatus();
        place.mark(outcome.newStatus(), actorUserId, clock.instant());
        attendances.saveAndFlush(place);
        audit.record(place, AuditAction.MARK, previous, outcome.newStatus(), method, actorUserId, role, null);
    }
}
