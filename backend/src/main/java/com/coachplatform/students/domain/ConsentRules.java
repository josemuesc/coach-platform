package com.coachplatform.students.domain;

import static com.coachplatform.students.domain.StudentRuleException.Code.CONSENT_VERSION_MISMATCH;
import static com.coachplatform.students.domain.StudentRuleException.Code.DATA_CONSENT_REQUIRED;
import static com.coachplatform.students.domain.StudentRuleException.Code.GUARDIAN_CONSENT_NOT_ALLOWED;
import static com.coachplatform.students.domain.StudentRuleException.Code.GUARDIAN_REQUIRED;

import com.coachplatform.students.api.Audience;
import com.coachplatform.students.api.ConsentType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What accepting an invitation records. The data authorization is mandatory and depends on the audience (DATA_ADULT for an
 * adult, DATA_GUARDIAN for a minor, accepted by the guardian); WHATSAPP is optional and independent. The version the
 * client submits must be the one in force; the hash recorded is the server's, never the client's.
 */
public final class ConsentRules {

    /** Where a registration comes from. A guardian authorization never comes from a logged-in student session. */
    public enum Channel {
        /** The public accept-invitation flow: the one-time token the coach handed to the guardian is the proof. */
        INVITATION,
        COACH_SESSION,
        STUDENT_SESSION
    }

    /**
     * DATA_GUARDIAN can be registered through the invitation flow or by the coach, never from an authenticated student
     * session (the day the minor gets their own access, they must not be able to authorize on their guardian's behalf).
     */
    public static void requireMayRegister(ConsentType type, Channel channel) {
        if (type == ConsentType.DATA_GUARDIAN && channel == Channel.STUDENT_SESSION) {
            throw new StudentRuleException(GUARDIAN_CONSENT_NOT_ALLOWED,
                    "The guardian's authorization cannot be registered from a student session");
        }
    }

    public static ConsentType dataTypeFor(Audience audience) {
        return audience == Audience.GUARDIAN ? ConsentType.DATA_GUARDIAN : ConsentType.DATA_ADULT;
    }

    public static List<ConsentType> offeredFor(Audience audience) {
        return List.of(dataTypeFor(audience), ConsentType.WHATSAPP);
    }

    public static boolean isRequired(ConsentType type) {
        return type != ConsentType.WHATSAPP;
    }

    /**
     * @param current the text in force for every type
     * @return the grants to record, data first; throws if the data authorization is declined or any accepted version is stale
     */
    public List<ConsentGrant> grants(Audience audience, Map<ConsentType, ConsentOffer> current,
                                     boolean acceptData, String dataVersion,
                                     boolean acceptWhatsapp, String whatsappVersion, GuardianData guardian) {
        if (!acceptData) {
            throw new StudentRuleException(DATA_CONSENT_REQUIRED, "The data authorization must be accepted to create the account");
        }
        List<ConsentGrant> grants = new ArrayList<>();
        ConsentGrant data = grant(current.get(dataTypeFor(audience)), dataVersion);
        if (audience == Audience.GUARDIAN) {
            // the signer is the guardian on file now: name and relationship are stored with the acceptance
            if (guardian == null || !guardian.isComplete()) {
                throw new StudentRuleException(GUARDIAN_REQUIRED, "The guardian's data is needed to record their authorization");
            }
            data = new ConsentGrant(data.type(), data.version(), data.textSha256(), guardian.name(), guardian.relationship());
        }
        grants.add(data);
        if (acceptWhatsapp) {
            grants.add(grant(current.get(ConsentType.WHATSAPP), whatsappVersion));
        }
        return grants;
    }

    private static ConsentGrant grant(ConsentOffer offer, String submittedVersion) {
        Objects.requireNonNull(offer, "no consent text in force");
        if (!offer.version().equals(submittedVersion)) {
            throw new StudentRuleException(CONSENT_VERSION_MISMATCH,
                    "The authorization text changed: review version " + offer.version() + " and accept it again");
        }
        return new ConsentGrant(offer.type(), offer.version(), offer.textSha256(), null, null);
    }
}
