package com.coachplatform.students.domain;

import static com.coachplatform.students.domain.StudentRuleException.Code.CONSENT_ALREADY_ACTIVE;
import static com.coachplatform.students.domain.StudentRuleException.Code.CONSENT_NOT_ACTIVE;

import com.coachplatform.students.api.ConsentType;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The current state of a student's consents. For each type, the latest event (acceptance or revocation) decides: active
 * only if that last event is an acceptance. On an exact tie of instants the revocation wins, the side that stops
 * messages and processing; with the student row locked and a real clock a tie does not happen in practice.
 *
 * <p>Accepting is refused while an acceptance is active (history is not duplicated) and revoking is refused when there is
 * nothing active to revoke. Revoking WHATSAPP stops notifications. Callers hold the student's row lock.
 */
public final class ConsentLedger {

    private static final Comparator<ConsentEvent> BY_TIME_THEN_REVOCATION_LAST = Comparator
            .comparing(ConsentEvent::at)
            .thenComparing(e -> e.kind() == ConsentEvent.Kind.REVOKED ? 1 : 0);

    public boolean isActive(ConsentType type, Collection<ConsentEvent> events) {
        Optional<ConsentEvent> latest = events.stream().filter(e -> e.type() == type).max(BY_TIME_THEN_REVOCATION_LAST);
        return latest.isPresent() && latest.get().kind() == ConsentEvent.Kind.ACCEPTED;
    }

    /** The types currently in force: what {@code GuardianRules.guardianIsHolder} and the age alert are computed from. */
    public Set<ConsentType> activeTypes(Collection<ConsentEvent> events) {
        Set<ConsentType> active = EnumSet.noneOf(ConsentType.class);
        for (ConsentType type : ConsentType.values()) {
            if (isActive(type, events)) {
                active.add(type);
            }
        }
        return active;
    }

    public void requireMayAccept(ConsentType type, Collection<ConsentEvent> events) {
        if (isActive(type, events)) {
            throw new StudentRuleException(CONSENT_ALREADY_ACTIVE, "The " + type + " authorization is already in force");
        }
    }

    public void requireMayRevoke(ConsentType type, Collection<ConsentEvent> events) {
        if (!isActive(type, events)) {
            throw new StudentRuleException(CONSENT_NOT_ACTIVE, "There is no " + type + " authorization in force to revoke");
        }
    }

    /** Notices may go out only while the WhatsApp authorization is in force. */
    public boolean mayNotify(Collection<ConsentEvent> events) {
        return isActive(ConsentType.WHATSAPP, events);
    }
}
