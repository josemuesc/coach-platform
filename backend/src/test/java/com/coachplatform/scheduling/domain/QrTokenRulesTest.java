package com.coachplatform.scheduling.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coachplatform.scheduling.domain.QrTokenRules.IssuedToken;
import com.coachplatform.scheduling.domain.SchedulingRuleException.Code;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QrTokenRulesTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    /** A whole minute, so also the first second of a 30 s window. */
    private static final Instant T0 = Instant.parse("2026-10-12T19:00:00Z");
    private static final UUID EVENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_EVENT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static QrTokenRules at(Instant now) {
        return new QrTokenRules(Clock.fixed(now, ZoneOffset.UTC), SECRET);
    }

    private static Code codeOf(Runnable action) {
        try {
            action.run();
        } catch (SchedulingRuleException e) {
            return e.code();
        }
        throw new AssertionError("expected a SchedulingRuleException");
    }

    private static void assertInvalid(QrTokenRules rules, String token) {
        assertThat(codeOf(() -> rules.verify(token))).isEqualTo(Code.INVALID_QR);
    }

    // ---- validity: current window and the previous one -----------------------------------------------

    @Test
    void aCodeVerifiesToTheEventItWasIssuedFor() {
        IssuedToken issued = at(T0).issue(EVENT);
        assertThat(at(T0).verify(issued.token())).isEqualTo(EVENT);
    }

    @Test
    void aCodeLivesThirtyToSixtySecondsAndNotOneSecondMore() {
        String token = at(T0).issue(EVENT).token();
        assertThatCode(() -> at(T0.plusSeconds(29)).verify(token)).doesNotThrowAnyException();     // same window
        assertThatCode(() -> at(T0.plusSeconds(30)).verify(token)).doesNotThrowAnyException();     // previous window
        assertThatCode(() -> at(T0.plusSeconds(59)).verify(token)).doesNotThrowAnyException();     // last second accepted
        assertInvalid(at(T0.plusSeconds(60)), token);
        assertInvalid(at(T0.plus(Duration.ofMinutes(10))), token);
    }

    @Test
    void validForSecondsReportsTheRealRemainingTime() {
        assertThat(at(T0).issue(EVENT).validForSeconds()).isEqualTo(60);
        assertThat(at(T0.plusSeconds(10)).issue(EVENT).validForSeconds()).isEqualTo(50);
        IssuedToken lastSecond = at(T0.plusSeconds(29)).issue(EVENT);
        assertThat(lastSecond.validForSeconds()).isEqualTo(31);
        assertThat(lastSecond.expiresAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void theReportedExpiryIsExactlyWhenTheServerStopsAcceptingIt() {
        for (int offset : new int[] {0, 7, 29, 30, 44}) {
            Instant now = T0.plusSeconds(offset);
            IssuedToken issued = at(now).issue(EVENT);
            assertThatCode(() -> at(issued.expiresAt().minusSeconds(1)).verify(issued.token())).doesNotThrowAnyException();
            assertInvalid(at(issued.expiresAt()), issued.token());
        }
    }

    @Test
    void aCodeFromTheFutureIsRejected() {
        String future = at(T0.plusSeconds(60)).issue(EVENT).token();
        assertInvalid(at(T0), future);
    }

    @Test
    void theCodeRotatesEveryWindowAndDiffersPerEvent() {
        assertThat(at(T0).issue(EVENT).token()).isEqualTo(at(T0.plusSeconds(29)).issue(EVENT).token());
        assertThat(at(T0).issue(EVENT).token()).isNotEqualTo(at(T0.plusSeconds(30)).issue(EVENT).token());
        assertThat(at(T0).issue(EVENT).token()).isNotEqualTo(at(T0).issue(OTHER_EVENT).token());
    }

    // ---- forged and malformed codes ---------------------------------------------------------------

    @Test
    void aCodeOfAnotherEventResolvesToThatOtherEventNeverToThisOne() {
        String other = at(T0).issue(OTHER_EVENT).token();
        assertThat(at(T0).verify(other)).isEqualTo(OTHER_EVENT).isNotEqualTo(EVENT);
    }

    @Test
    void changingTheEventInsideAGenuineCodeBreaksIt() {
        byte[] raw = Base64.getUrlDecoder().decode(at(T0).issue(EVENT).token());
        byte[] forged = raw.clone();
        byte[] other = Base64.getUrlDecoder().decode(at(T0).issue(OTHER_EVENT).token());
        System.arraycopy(other, 0, forged, 0, 16);   // event of B, mac of A
        assertInvalid(at(T0), Base64.getUrlEncoder().withoutPadding().encodeToString(forged));
    }

    @Test
    void anOldMacCannotBeRenewedByEditingTheWindow() {
        byte[] raw = Base64.getUrlDecoder().decode(at(T0).issue(EVENT).token());
        byte[] fresh = Base64.getUrlDecoder().decode(at(T0.plusSeconds(120)).issue(EVENT).token());
        byte[] forged = raw.clone();
        System.arraycopy(fresh, 16, forged, 16, 8);   // current window number, old mac
        assertInvalid(at(T0.plusSeconds(120)), Base64.getUrlEncoder().withoutPadding().encodeToString(forged));
    }

    @Test
    void everySingleFlippedByteIsRejected() {
        byte[] raw = Base64.getUrlDecoder().decode(at(T0).issue(EVENT).token());
        QrTokenRules rules = at(T0);
        for (int i = 0; i < raw.length; i++) {
            byte[] forged = raw.clone();
            forged[i] ^= 0x01;
            assertInvalid(rules, Base64.getUrlEncoder().withoutPadding().encodeToString(forged));
        }
    }

    @Test
    void aCodeSignedWithAnotherSecretIsRejected() {
        QrTokenRules stranger = new QrTokenRules(Clock.fixed(T0, ZoneOffset.UTC), "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.US_ASCII));
        assertInvalid(at(T0), stranger.issue(EVENT).token());
    }

    @Test
    void garbageNullAndWrongLengthAreAllTheSameInvalidCode() {
        QrTokenRules rules = at(T0);
        String genuine = rules.issue(EVENT).token();
        for (String bad : new String[] {null, "", "   ", "not-a-token", "%%%", genuine.substring(1), genuine + "A", "A".repeat(500)}) {
            assertInvalid(rules, bad);
        }
    }

    @Test
    void theCanonicalPaddedFormOfAGenuineCodeIsTheSameCodeNotANewOne() {
        QrTokenRules rules = at(T0);
        assertThat(rules.verify(rules.issue(EVENT).token() + "=")).isEqualTo(EVENT);
    }

    @Test
    void everyFailureCarriesTheSameMessage() {
        QrTokenRules rules = at(T0);
        String expired = at(T0.minusSeconds(300)).issue(EVENT).token();
        String message = messageOf(() -> rules.verify(expired));
        assertThat(messageOf(() -> rules.verify("garbage"))).isEqualTo(message);
        assertThat(messageOf(() -> rules.verify(null))).isEqualTo(message);
    }

    private static String messageOf(Runnable action) {
        try {
            action.run();
        } catch (SchedulingRuleException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected a SchedulingRuleException");
    }

    // ---- secrets ----------------------------------------------------------------------------------

    @Test
    void theTokenNeverAppearsInToString() {
        IssuedToken issued = at(T0).issue(EVENT);
        assertThat(issued.toString()).doesNotContain(issued.token()).contains("<redacted>");
    }

    @Test
    void aShortOrMissingSecretIsRefused() {
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        assertThatThrownBy(() -> new QrTokenRules(clock, new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QrTokenRules(clock, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
