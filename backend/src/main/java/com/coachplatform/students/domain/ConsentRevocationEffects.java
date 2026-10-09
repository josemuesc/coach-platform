package com.coachplatform.students.domain;

import com.coachplatform.students.api.ConsentType;
import java.util.EnumSet;
import java.util.Set;

/**
 * What revoking a consent causes. WHATSAPP: notifications stop, nothing else. A DATA consent (adult or guardian): the
 * account is suspended and marked for anonymization, unless another data consent is still in force (a superseded
 * DATA_GUARDIAN revoked after the student authorized as an adult changes nothing). Consent records and the audit are
 * ALWAYS kept. The anonymization itself is not built: for now the student is only marked.
 */
public final class ConsentRevocationEffects {

    public enum Effect {
        STOP_NOTIFICATIONS,
        SUSPEND_ACCOUNT,
        MARK_FOR_ANONYMIZATION
    }

    private ConsentRevocationEffects() {
    }

    public static boolean isData(ConsentType type) {
        return type == ConsentType.DATA_ADULT || type == ConsentType.DATA_GUARDIAN;
    }

    /** @param stillActive the consent types in force AFTER the revocation */
    public static Set<Effect> of(ConsentType revoked, Set<ConsentType> stillActive) {
        Set<Effect> effects = EnumSet.noneOf(Effect.class);
        if (revoked == ConsentType.WHATSAPP) {
            effects.add(Effect.STOP_NOTIFICATIONS);
        } else if (stillActive.stream().noneMatch(ConsentRevocationEffects::isData)) {
            effects.add(Effect.SUSPEND_ACCOUNT);
            effects.add(Effect.MARK_FOR_ANONYMIZATION);
        }
        return effects;
    }

    /** A student marked for anonymization cannot give a data authorization again. */
    public static void requireMayAcceptData(ConsentType type, boolean markedForAnonymization) {
        if (isData(type) && markedForAnonymization) {
            throw new StudentRuleException(StudentRuleException.Code.ANONYMIZATION_PENDING,
                    "The account is marked for anonymization: a data authorization can no longer be given");
        }
    }
}
