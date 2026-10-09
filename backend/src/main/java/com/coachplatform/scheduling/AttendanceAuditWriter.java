package com.coachplatform.scheduling;

import com.coachplatform.scheduling.api.ActorRole;
import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.api.AuditAction;
import com.coachplatform.scheduling.api.AuditMethod;
import com.coachplatform.scheduling.domain.AttendanceAuditRules;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes the audit line of a change, in the caller's transaction, with the time of the injected Clock. A combination that no
 * business flow produces is refused by {@link AttendanceAuditRules} (a programming error) before anything is written.
 */
@Component
class AttendanceAuditWriter {

    private final AttendanceAuditRules rules = new AttendanceAuditRules();
    private final AttendanceAuditRepository audits;
    private final Clock clock;

    AttendanceAuditWriter(AttendanceAuditRepository audits, Clock clock) {
        this.audits = audits;
        this.clock = clock;
    }

    void record(SessionAttendance place, AuditAction action, AttendanceStatus previous, AttendanceStatus next, AuditMethod method,
                UUID actorUserId, ActorRole actorRole, String reason) {
        rules.validate(action, previous, next, method, actorRole, reason);
        audits.save(new AttendanceAudit(place.getId(), action, previous, next, method, actorUserId, actorRole, reason, clock.instant()));
    }
}
