package com.coachplatform.students.domain;

import static com.coachplatform.students.api.ConsentType.DATA_ADULT;
import static com.coachplatform.students.api.ConsentType.DATA_GUARDIAN;
import static com.coachplatform.students.api.ConsentType.WHATSAPP;
import static com.coachplatform.students.domain.ConsentRevocationEffects.Effect.MARK_FOR_ANONYMIZATION;
import static com.coachplatform.students.domain.ConsentRevocationEffects.Effect.STOP_NOTIFICATIONS;
import static com.coachplatform.students.domain.ConsentRevocationEffects.Effect.SUSPEND_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.students.api.ConsentType;
import com.coachplatform.students.domain.StudentRuleException.Code;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConsentRevocationEffectsTest {

    @Test
    void revokingWhatsappOnlyStopsNotifications() {
        assertThat(ConsentRevocationEffects.of(WHATSAPP, Set.of(DATA_ADULT))).containsExactly(STOP_NOTIFICATIONS);
        assertThat(ConsentRevocationEffects.of(WHATSAPP, Set.of())).containsExactly(STOP_NOTIFICATIONS);
    }

    @Test
    void revokingTheOnlyDataConsentSuspendsTheAccountAndMarksItForAnonymization() {
        assertThat(ConsentRevocationEffects.of(DATA_ADULT, Set.of())).containsExactlyInAnyOrder(SUSPEND_ACCOUNT, MARK_FOR_ANONYMIZATION);
        assertThat(ConsentRevocationEffects.of(DATA_GUARDIAN, Set.of(WHATSAPP))).containsExactlyInAnyOrder(SUSPEND_ACCOUNT, MARK_FOR_ANONYMIZATION);
    }

    @Test
    void revokingADataConsentDoesNotStopNotificationsByItself() {
        assertThat(ConsentRevocationEffects.of(DATA_ADULT, Set.of(WHATSAPP))).doesNotContain(STOP_NOTIFICATIONS);
    }

    @Test
    void revokingASupersededGuardianConsentChangesNothingWhileTheAdultOneIsInForce() {
        assertThat(ConsentRevocationEffects.of(DATA_GUARDIAN, Set.of(DATA_ADULT))).isEmpty();
        assertThat(ConsentRevocationEffects.of(DATA_ADULT, Set.of(DATA_GUARDIAN))).isEmpty();
    }

    @Test
    void anAccountMarkedForAnonymizationCannotGiveADataAuthorizationAgain() {
        for (ConsentType type : new ConsentType[] {DATA_ADULT, DATA_GUARDIAN}) {
            try {
                ConsentRevocationEffects.requireMayAcceptData(type, true);
                throw new AssertionError("expected ANONYMIZATION_PENDING");
            } catch (StudentRuleException e) {
                assertThat(e.code()).isEqualTo(Code.ANONYMIZATION_PENDING);
            }
            ConsentRevocationEffects.requireMayAcceptData(type, false);
        }
        ConsentRevocationEffects.requireMayAcceptData(WHATSAPP, true);
    }
}
