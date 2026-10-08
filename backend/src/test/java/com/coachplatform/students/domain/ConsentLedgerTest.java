package com.coachplatform.students.domain;

import static com.coachplatform.students.api.ConsentType.DATA_ADULT;
import static com.coachplatform.students.api.ConsentType.DATA_GUARDIAN;
import static com.coachplatform.students.api.ConsentType.WHATSAPP;
import static com.coachplatform.students.domain.ConsentEvent.Kind.ACCEPTED;
import static com.coachplatform.students.domain.ConsentEvent.Kind.REVOKED;
import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.students.api.ConsentType;
import com.coachplatform.students.domain.StudentRuleException.Code;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsentLedgerTest {

    private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-02T10:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-03T10:00:00Z");
    private final ConsentLedger ledger = new ConsentLedger();

    private static ConsentEvent accepted(ConsentType type, Instant at) {
        return new ConsentEvent(type, ACCEPTED, at);
    }

    private static ConsentEvent revoked(ConsentType type, Instant at) {
        return new ConsentEvent(type, REVOKED, at);
    }

    private static Code codeOf(Runnable action) {
        try {
            action.run();
        } catch (StudentRuleException e) {
            return e.code();
        }
        throw new AssertionError("expected a StudentRuleException");
    }

    @Test
    void nothingRecordedMeansNothingInForce() {
        assertThat(ledger.isActive(WHATSAPP, List.of())).isFalse();
        assertThat(ledger.activeTypes(List.of())).isEmpty();
    }

    @Test
    void anAcceptanceIsInForceUntilItIsRevoked() {
        assertThat(ledger.isActive(WHATSAPP, List.of(accepted(WHATSAPP, T1)))).isTrue();
        assertThat(ledger.isActive(WHATSAPP, List.of(accepted(WHATSAPP, T1), revoked(WHATSAPP, T2)))).isFalse();
    }

    @Test
    void theLatestEventDecidesNoMatterTheOrderOfTheList() {
        List<ConsentEvent> events = List.of(revoked(WHATSAPP, T2), accepted(WHATSAPP, T3), accepted(WHATSAPP, T1));
        assertThat(ledger.isActive(WHATSAPP, events)).isTrue();
        List<ConsentEvent> revokedLast = List.of(accepted(WHATSAPP, T3 .minusSeconds(1)), revoked(WHATSAPP, T3), accepted(WHATSAPP, T1));
        assertThat(ledger.isActive(WHATSAPP, revokedLast)).isFalse();
    }

    @Test
    void acceptingAgainAfterARevocationReactivatesIt() {
        assertThat(ledger.isActive(WHATSAPP, List.of(accepted(WHATSAPP, T1), revoked(WHATSAPP, T2), accepted(WHATSAPP, T3)))).isTrue();
    }

    @Test
    void anExactTieGoesToTheRevocation() {
        assertThat(ledger.isActive(WHATSAPP, List.of(accepted(WHATSAPP, T1), revoked(WHATSAPP, T1)))).isFalse();
        assertThat(ledger.isActive(WHATSAPP, List.of(revoked(WHATSAPP, T1), accepted(WHATSAPP, T1)))).isFalse();
    }

    @Test
    void eachTypeHasItsOwnState() {
        List<ConsentEvent> events = List.of(accepted(DATA_GUARDIAN, T1), accepted(WHATSAPP, T1), revoked(WHATSAPP, T2));
        assertThat(ledger.activeTypes(events)).containsExactly(DATA_GUARDIAN);
        assertThat(ledger.isActive(DATA_ADULT, events)).isFalse();
    }

    @Test
    void acceptingWhileAnAcceptanceIsActiveIsRefused() {
        assertThat(codeOf(() -> ledger.requireMayAccept(WHATSAPP, List.of(accepted(WHATSAPP, T1))))).isEqualTo(Code.CONSENT_ALREADY_ACTIVE);
        assertThat(codeOf(() -> ledger.requireMayAccept(WHATSAPP, List.of(accepted(WHATSAPP, T1), revoked(WHATSAPP, T2), accepted(WHATSAPP, T3)))))
                .isEqualTo(Code.CONSENT_ALREADY_ACTIVE);
    }

    @Test
    void acceptingIsAllowedWhenNothingIsActive() {
        ledger.requireMayAccept(WHATSAPP, List.of());
        ledger.requireMayAccept(WHATSAPP, List.of(accepted(WHATSAPP, T1), revoked(WHATSAPP, T2)));
        ledger.requireMayAccept(DATA_ADULT, List.of(accepted(WHATSAPP, T1)));   // another type being active does not matter
    }

    @Test
    void revokingNeedsSomethingActive() {
        assertThat(codeOf(() -> ledger.requireMayRevoke(WHATSAPP, List.of()))).isEqualTo(Code.CONSENT_NOT_ACTIVE);
        assertThat(codeOf(() -> ledger.requireMayRevoke(WHATSAPP, List.of(accepted(WHATSAPP, T1), revoked(WHATSAPP, T2)))))
                .isEqualTo(Code.CONSENT_NOT_ACTIVE);
        assertThat(codeOf(() -> ledger.requireMayRevoke(WHATSAPP, List.of(accepted(DATA_ADULT, T1))))).isEqualTo(Code.CONSENT_NOT_ACTIVE);
        ledger.requireMayRevoke(WHATSAPP, List.of(accepted(WHATSAPP, T1)));
    }

    @Test
    void revokingWhatsappStopsNotifications() {
        List<ConsentEvent> optedIn = List.of(accepted(DATA_ADULT, T1), accepted(WHATSAPP, T1));
        assertThat(ledger.mayNotify(optedIn)).isTrue();
        assertThat(ledger.mayNotify(List.of(accepted(DATA_ADULT, T1), accepted(WHATSAPP, T1), revoked(WHATSAPP, T2)))).isFalse();
        assertThat(ledger.mayNotify(List.of(accepted(DATA_ADULT, T1)))).isFalse();   // never opted in
    }

    @Test
    void revokingTheDataAuthorizationDoesNotSilentlyRevokeWhatsapp() {
        // the ledger only reports facts; what a data revocation implies for the rest is a service decision
        List<ConsentEvent> events = List.of(accepted(DATA_ADULT, T1), accepted(WHATSAPP, T1), revoked(DATA_ADULT, T2));
        assertThat(ledger.isActive(DATA_ADULT, events)).isFalse();
        assertThat(ledger.isActive(WHATSAPP, events)).isTrue();
    }
}
