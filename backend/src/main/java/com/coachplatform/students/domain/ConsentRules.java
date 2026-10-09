package com.coachplatform.students.domain;

import static com.coachplatform.students.domain.StudentRuleException.Code.CONSENT_VERSION_MISMATCH;
import static com.coachplatform.students.domain.StudentRuleException.Code.DATA_CONSENT_REQUIRED;
import static com.coachplatform.students.domain.StudentRuleException.Code.ADULT_CONSENT_NOT_ALLOWED;
import static com.coachplatform.students.domain.StudentRuleException.Code.CONSENT_NOT_APPLICABLE;
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
     * Who may register what. DATA_GUARDIAN: through the invitation or by the coach, never from a logged-in student session
     * (the day the minor gets their own access, they must not authorize on their guardian's behalf). DATA_ADULT: through
     * the invitation or by the adult student themselves; never by the coach, and never from an account the guardian still
     * holds. WHATSAPP: the student session (the holder of the account) or the invitation.
     *
     * @param guardianIsHolder the guardian still holds this student's account
     */
    public static void requireMayRegister(ConsentType type, Channel channel, boolean guardianIsHolder) {
        if (type == ConsentType.DATA_GUARDIAN && channel == Channel.STUDENT_SESSION) {
            throw new StudentRuleException(GUARDIAN_CONSENT_NOT_ALLOWED,
                    "The guardian's authorization cannot be registered from a student session");
        }
        if (type == ConsentType.DATA_ADULT && channel != Channel.INVITATION
                && (channel == Channel.COACH_SESSION || guardianIsHolder)) {
            throw new StudentRuleException(ADULT_CONSENT_NOT_ALLOWED,
                    "The adult authorization can only be given by the student themselves, from their own account");
        }
        if (type == ConsentType.WHATSAPP && channel == Channel.COACH_SESSION) {
            throw new StudentRuleException(ADULT_CONSENT_NOT_ALLOWED,
                    "The WhatsApp authorization is given by the student or their guardian, not by the coach");
        }
    }

    /** DATA_ADULT only applies to an adult, DATA_GUARDIAN only to a minor; WHATSAPP to both. */
    public static void requireTypeApplies(ConsentType type, Audience audience) {
        if (type != ConsentType.WHATSAPP && type != dataTypeFor(audience)) {
            throw new StudentRuleException(CONSENT_NOT_APPLICABLE, "The " + type + " authorization does not apply to this student");
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
        grants.add(grant(current.get(dataTypeFor(audience)), dataVersion, guardian));
        if (acceptWhatsapp) {
            grants.add(grant(current.get(ConsentType.WHATSAPP), whatsappVersion, guardian));
        }
        return grants;
    }

    /**
     * One authorization to record. The submitted version must be the one in force. For DATA_GUARDIAN the signer is the
     * guardian on file right now (name and relationship are stored with the acceptance); the other types carry no signer.
     */
    public static ConsentGrant grant(ConsentOffer offer, String submittedVersion, GuardianData guardian) {
        Objects.requireNonNull(offer, "no consent text in force");
        if (!offer.version().equals(submittedVersion)) {
            throw new StudentRuleException(CONSENT_VERSION_MISMATCH,
                    "The authorization text changed: review version " + offer.version() + " and accept it again");
        }
        if (offer.type() == ConsentType.DATA_GUARDIAN) {
            if (guardian == null || !guardian.isComplete()) {
                throw new StudentRuleException(GUARDIAN_REQUIRED, "The guardian's data is needed to record their authorization");
            }
            return new ConsentGrant(offer.type(), offer.version(), offer.textSha256(), guardian.name(), guardian.relationship());
        }
        return new ConsentGrant(offer.type(), offer.version(), offer.textSha256(), null, null);
    }
}
