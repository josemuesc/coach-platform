package com.coachplatform.students.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.students.api.Audience;
import com.coachplatform.students.api.ConsentType;
import com.coachplatform.students.domain.StudentRuleException.Code;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsentRulesTest {

    private static final Map<ConsentType, ConsentOffer> CURRENT = Map.of(
            ConsentType.DATA_ADULT, new ConsentOffer(ConsentType.DATA_ADULT, "v2", "hash-adult"),
            ConsentType.DATA_GUARDIAN, new ConsentOffer(ConsentType.DATA_GUARDIAN, "v3", "hash-guardian"),
            ConsentType.WHATSAPP, new ConsentOffer(ConsentType.WHATSAPP, "v1", "hash-wa"));

    private static final GuardianData MARTA = new GuardianData("Marta Perez", "madre", "3001234567", "marta@example.com");

    private final ConsentRules rules = new ConsentRules();

    private static Code codeOf(Runnable action) {
        try {
            action.run();
        } catch (StudentRuleException e) {
            return e.code();
        }
        throw new AssertionError("expected a StudentRuleException");
    }

    @Test
    void anAdultRecordsTheAdultAuthorizationAndOptionallyWhatsapp() {
        assertThat(rules.grants(Audience.ADULT, CURRENT, true, "v2", true, "v1", GuardianData.NONE)).containsExactly(
                new ConsentGrant(ConsentType.DATA_ADULT, "v2", "hash-adult", null, null), new ConsentGrant(ConsentType.WHATSAPP, "v1", "hash-wa", null, null));
        assertThat(rules.grants(Audience.ADULT, CURRENT, true, "v2", false, null, GuardianData.NONE))
                .containsExactly(new ConsentGrant(ConsentType.DATA_ADULT, "v2", "hash-adult", null, null));
    }

    @Test
    void aGuardianRecordsTheGuardianAuthorizationNeverTheAdultOne() {
        List<ConsentGrant> grants = rules.grants(Audience.GUARDIAN, CURRENT, true, "v3", true, "v1", MARTA);
        assertThat(grants).extracting(ConsentGrant::type).containsExactly(ConsentType.DATA_GUARDIAN, ConsentType.WHATSAPP);
        assertThat(grants).extracting(ConsentGrant::type).doesNotContain(ConsentType.DATA_ADULT);
    }

    @Test
    void theDataAuthorizationIsMandatory() {
        assertThat(codeOf(() -> rules.grants(Audience.ADULT, CURRENT, false, "v2", true, "v1", GuardianData.NONE))).isEqualTo(Code.DATA_CONSENT_REQUIRED);
        assertThat(codeOf(() -> rules.grants(Audience.GUARDIAN, CURRENT, false, null, false, null, MARTA))).isEqualTo(Code.DATA_CONSENT_REQUIRED);
    }

    @Test
    void aStaleDataVersionIsRejected() {
        assertThat(codeOf(() -> rules.grants(Audience.ADULT, CURRENT, true, "v1", false, null, GuardianData.NONE))).isEqualTo(Code.CONSENT_VERSION_MISMATCH);
        assertThat(codeOf(() -> rules.grants(Audience.ADULT, CURRENT, true, null, false, null, GuardianData.NONE))).isEqualTo(Code.CONSENT_VERSION_MISMATCH);
        // the guardian cannot accept with the version of the adult text
        assertThat(codeOf(() -> rules.grants(Audience.GUARDIAN, CURRENT, true, "v2", false, null, MARTA))).isEqualTo(Code.CONSENT_VERSION_MISMATCH);
    }

    @Test
    void aStaleWhatsappVersionOnlyMattersWhenAccepting() {
        assertThat(codeOf(() -> rules.grants(Audience.ADULT, CURRENT, true, "v2", true, "old", GuardianData.NONE))).isEqualTo(Code.CONSENT_VERSION_MISMATCH);
        assertThat(rules.grants(Audience.ADULT, CURRENT, true, "v2", false, "old", GuardianData.NONE)).hasSize(1);
    }

    @Test
    void theHashRecordedIsTheServersNeverTheClients() {
        assertThat(rules.grants(Audience.ADULT, CURRENT, true, "v2", false, null, GuardianData.NONE).get(0).textSha256()).isEqualTo("hash-adult");
    }

    @Test
    void whichAuthorizationsAreOfferedAndRequired() {
        assertThat(ConsentRules.offeredFor(Audience.ADULT)).containsExactly(ConsentType.DATA_ADULT, ConsentType.WHATSAPP);
        assertThat(ConsentRules.offeredFor(Audience.GUARDIAN)).containsExactly(ConsentType.DATA_GUARDIAN, ConsentType.WHATSAPP);
        assertThat(ConsentRules.isRequired(ConsentType.DATA_ADULT)).isTrue();
        assertThat(ConsentRules.isRequired(ConsentType.DATA_GUARDIAN)).isTrue();
        assertThat(ConsentRules.isRequired(ConsentType.WHATSAPP)).isFalse();
    }

    // ---- the signer of a guardian authorization ---------------------------------------------------------

    @Test
    void theGuardiansAuthorizationCarriesTheSignerOnFileAndTheOthersDoNot() {
        List<ConsentGrant> grants = rules.grants(Audience.GUARDIAN, CURRENT, true, "v3", true, "v1", MARTA);
        assertThat(grants.get(0)).isEqualTo(new ConsentGrant(ConsentType.DATA_GUARDIAN, "v3", "hash-guardian", "Marta Perez", "madre"));
        assertThat(grants.get(1).signerName()).isNull();                 // WHATSAPP
        assertThat(grants.get(1).signerRelationship()).isNull();
        assertThat(rules.grants(Audience.ADULT, CURRENT, true, "v2", false, null, MARTA).get(0).signerName()).isNull();   // DATA_ADULT
    }

    @Test
    void aGuardianAuthorizationNeedsTheGuardianToBeOnFile() {
        assertThat(codeOf(() -> rules.grants(Audience.GUARDIAN, CURRENT, true, "v3", false, null, GuardianData.NONE)))
                .isEqualTo(Code.GUARDIAN_REQUIRED);
        assertThat(codeOf(() -> rules.grants(Audience.GUARDIAN, CURRENT, true, "v3", false, null, null))).isEqualTo(Code.GUARDIAN_REQUIRED);
    }

    // ---- who may register what ----------------------------------------------------------------------------

    @Test
    void aGuardianAuthorizationCanNeverBeRegisteredFromAStudentSession() {
        assertThat(codeOf(() -> ConsentRules.requireMayRegister(ConsentType.DATA_GUARDIAN, ConsentRules.Channel.STUDENT_SESSION)))
                .isEqualTo(Code.GUARDIAN_CONSENT_NOT_ALLOWED);
    }

    @Test
    void aGuardianAuthorizationComesFromTheInvitationOrTheCoach() {
        ConsentRules.requireMayRegister(ConsentType.DATA_GUARDIAN, ConsentRules.Channel.INVITATION);
        ConsentRules.requireMayRegister(ConsentType.DATA_GUARDIAN, ConsentRules.Channel.COACH_SESSION);
    }

    @Test
    void theOtherTypesAreNotRestrictedByThisRule() {
        for (ConsentType type : new ConsentType[] {ConsentType.DATA_ADULT, ConsentType.WHATSAPP}) {
            for (ConsentRules.Channel channel : ConsentRules.Channel.values()) {
                ConsentRules.requireMayRegister(type, channel);
            }
        }
    }
}
