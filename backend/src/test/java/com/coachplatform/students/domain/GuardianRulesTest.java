package com.coachplatform.students.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.coachplatform.students.api.Audience;
import com.coachplatform.students.api.ConsentType;
import com.coachplatform.students.domain.GuardianRules.AgeAlert;
import com.coachplatform.students.domain.GuardianRules.AgeStatus;
import com.coachplatform.students.domain.StudentRuleException.Code;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GuardianRulesTest {

    /** 2026-10-08 10:00 in Bogota. */
    private static final Instant NOW = Instant.parse("2026-10-08T15:00:00Z");
    private static final GuardianData FULL = new GuardianData("Marta Perez", "madre", "3001234567", "Marta@Example.com");
    private static final Set<ConsentType> NOTHING = Set.of();

    private static GuardianRules rules() {
        return rulesAt(NOW);
    }

    private static GuardianRules rulesAt(Instant now) {
        return new GuardianRules(Clock.fixed(now, ZoneOffset.UTC));
    }

    private static Code codeOf(Runnable action) {
        try {
            action.run();
        } catch (StudentRuleException e) {
            return e.code();
        }
        throw new AssertionError("expected a StudentRuleException");
    }

    // ---- when is someone a minor -----------------------------------------------------------------------

    @Test
    void turningEighteenTodayMakesAnAdultButTomorrowStillAMinor() {
        assertThat(rules().isMinor(LocalDate.of(2008, 10, 8))).isFalse();
        assertThat(rules().isMinor(LocalDate.of(2008, 10, 9))).isTrue();
        assertThat(rules().isMinor(LocalDate.of(2008, 10, 7))).isFalse();
    }

    @Test
    void theDayIsTheBogotaDayNeverTheUtcDay() {
        Instant lateEveningInBogota = Instant.parse("2026-10-09T03:00:00Z");   // 22:00 on 8 October in Bogota, already 9 in UTC
        assertThat(rulesAt(lateEveningInBogota).today()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(rulesAt(lateEveningInBogota).isMinor(LocalDate.of(2008, 10, 9))).isTrue();   // would be an adult by the UTC day
        Instant justAfterMidnightInBogota = Instant.parse("2026-10-09T05:00:00Z");
        assertThat(rulesAt(justAfterMidnightInBogota).isMinor(LocalDate.of(2008, 10, 9))).isFalse();
    }

    @Test
    void aLeapDayBirthReachesEighteenOnMarchFirstWhenThereIsNoTwentyNinth() {
        assertThat(rules().turnsAdultOn(LocalDate.of(2008, 2, 29))).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(rules().turnsAdultOn(LocalDate.of(2010, 2, 28))).isEqualTo(LocalDate.of(2028, 2, 28));
        assertThat(rulesAt(Instant.parse("2026-03-01T05:00:00Z")).isMinor(LocalDate.of(2008, 2, 29))).isFalse();
        assertThat(rulesAt(Instant.parse("2026-02-28T20:00:00Z")).isMinor(LocalDate.of(2008, 2, 29))).isTrue();
    }

    @Test
    void theAudienceAndRequiredAuthorizationFollowTheAge() {
        assertThat(rules().audienceFor(LocalDate.of(2010, 5, 1))).isEqualTo(Audience.GUARDIAN);
        assertThat(rules().audienceFor(LocalDate.of(1995, 5, 1))).isEqualTo(Audience.ADULT);
        assertThat(rules().requiredDataConsent(LocalDate.of(2010, 5, 1))).isEqualTo(ConsentType.DATA_GUARDIAN);
        assertThat(rules().requiredDataConsent(LocalDate.of(1995, 5, 1))).isEqualTo(ConsentType.DATA_ADULT);
    }

    // ---- the birth date ----------------------------------------------------------------------------------

    @Test
    void theBirthDateIsMandatoryAndMustBeReal() {
        assertThat(codeOf(() -> rules().requireValidBirthDate(null))).isEqualTo(Code.INVALID_BIRTH_DATE);
        assertThat(codeOf(() -> rules().requireValidBirthDate(LocalDate.of(2026, 10, 9)))).isEqualTo(Code.INVALID_BIRTH_DATE);
        assertThat(codeOf(() -> rules().requireValidBirthDate(LocalDate.of(1899, 12, 31)))).isEqualTo(Code.INVALID_BIRTH_DATE);
        assertThatCode(() -> rules().requireValidBirthDate(LocalDate.of(2026, 10, 8))).doesNotThrowAnyException();
        assertThatCode(() -> rules().requireValidBirthDate(LocalDate.of(1900, 1, 1))).doesNotThrowAnyException();
    }

    @Test
    void theBirthDateIsCheckedAgainstTheBogotaDay() {
        Instant lateEveningInBogota = Instant.parse("2026-10-09T03:00:00Z");
        assertThat(codeOf(() -> rulesAt(lateEveningInBogota).requireValidBirthDate(LocalDate.of(2026, 10, 9))))
                .isEqualTo(Code.INVALID_BIRTH_DATE);
    }

    // ---- guardian data -----------------------------------------------------------------------------------

    @Test
    void aMinorNeedsAllFourGuardianFields() {
        LocalDate minor = LocalDate.of(2010, 5, 1);
        assertThat(codeOf(() -> rules().requireGuardianData(minor, GuardianData.NONE))).isEqualTo(Code.GUARDIAN_REQUIRED);
        assertThat(codeOf(() -> rules().requireGuardianData(minor, new GuardianData("Marta", "madre", "300", null))))
                .isEqualTo(Code.GUARDIAN_INCOMPLETE);
        assertThat(codeOf(() -> rules().requireGuardianData(minor, new GuardianData("Marta", " ", "300", "m@x.co"))))
                .isEqualTo(Code.GUARDIAN_INCOMPLETE);
        assertThatCode(() -> rules().requireGuardianData(minor, FULL)).doesNotThrowAnyException();
    }

    @Test
    void anAdultNeedsNoGuardianButIfGivenItMustBeComplete() {
        LocalDate adult = LocalDate.of(1995, 5, 1);
        assertThatCode(() -> rules().requireGuardianData(adult, GuardianData.NONE)).doesNotThrowAnyException();
        assertThatCode(() -> rules().requireGuardianData(adult, FULL)).doesNotThrowAnyException();
        assertThat(codeOf(() -> rules().requireGuardianData(adult, new GuardianData("Marta", null, null, null))))
                .isEqualTo(Code.GUARDIAN_INCOMPLETE);
    }

    @Test
    void theGuardianEmailIsNormalizedAndBlanksCountAsMissing() {
        assertThat(FULL.email()).isEqualTo("marta@example.com");
        assertThat(new GuardianData("  ", "", null, " ").isEmpty()).isTrue();
        assertThat(FULL.isComplete()).isTrue();
    }

    // ---- editing the birth date after the invitation was accepted -------------------------------------------

    @Test
    void movingBetweenAdultAndMinorIsBlockedInBothDirectionsOnceAccepted() {
        LocalDate minor = LocalDate.of(2010, 5, 1);
        LocalDate adult = LocalDate.of(1995, 5, 1);
        assertThat(codeOf(() -> rules().requireAudienceChangeAllowed(adult, minor, true))).isEqualTo(Code.AUDIENCE_CHANGE_BLOCKED);
        assertThat(codeOf(() -> rules().requireAudienceChangeAllowed(minor, adult, true))).isEqualTo(Code.AUDIENCE_CHANGE_BLOCKED);
    }

    @Test
    void theSameModalityOrNoAcceptedInvitationMayBeEdited() {
        LocalDate minor = LocalDate.of(2010, 5, 1);
        LocalDate adult = LocalDate.of(1995, 5, 1);
        assertThatCode(() -> rules().requireAudienceChangeAllowed(adult, minor, false)).doesNotThrowAnyException();
        assertThatCode(() -> rules().requireAudienceChangeAllowed(minor, adult, false)).doesNotThrowAnyException();
        assertThatCode(() -> rules().requireAudienceChangeAllowed(minor, LocalDate.of(2009, 6, 1), true)).doesNotThrowAnyException();
        assertThatCode(() -> rules().requireAudienceChangeAllowed(adult, LocalDate.of(1990, 1, 1), true)).doesNotThrowAnyException();
    }

    // ---- who holds the account ------------------------------------------------------------------------------

    @Test
    void theGuardianHoldsTheAccountOfAMinor() {
        LocalDate minor = LocalDate.of(2010, 5, 1);
        assertThat(rules().guardianIsHolder(minor, NOTHING)).isTrue();
        assertThat(rules().accessEmail(minor, NOTHING, "kid@x.co", FULL)).isEqualTo("marta@example.com");
        assertThat(rules().notificationPhone(minor, NOTHING, "3110000000", FULL)).isEqualTo("3001234567");
    }

    @Test
    void anAdultUsesTheirOwnContacts() {
        LocalDate adult = LocalDate.of(1995, 5, 1);
        assertThat(rules().guardianIsHolder(adult, Set.of(ConsentType.DATA_ADULT))).isFalse();
        assertThat(rules().accessEmail(adult, Set.of(ConsentType.DATA_ADULT), "ana@x.co", GuardianData.NONE)).isEqualTo("ana@x.co");
        assertThat(rules().notificationPhone(adult, NOTHING, "3110000000", GuardianData.NONE)).isEqualTo("3110000000");
    }

    @Test
    void afterTurningEighteenTheGuardianStaysHolderUntilTheStudentAuthorizes() {
        LocalDate justAdult = LocalDate.of(2008, 1, 1);
        assertThat(rules().guardianIsHolder(justAdult, Set.of(ConsentType.DATA_GUARDIAN))).isTrue();
        assertThat(rules().notificationPhone(justAdult, Set.of(ConsentType.DATA_GUARDIAN), "3110000000", FULL)).isEqualTo("3001234567");
        assertThat(rules().guardianIsHolder(justAdult, Set.of(ConsentType.DATA_GUARDIAN, ConsentType.DATA_ADULT))).isFalse();
        assertThat(rules().notificationPhone(justAdult, Set.of(ConsentType.DATA_GUARDIAN, ConsentType.DATA_ADULT), "3110000000", FULL))
                .isEqualTo("3110000000");
    }

    @Test
    void withoutGuardianDataTheStudentsOwnContactIsUsed() {
        assertThat(rules().accessEmail(LocalDate.of(2010, 5, 1), NOTHING, "kid@x.co", GuardianData.NONE)).isEqualTo("kid@x.co");
    }

    // ---- turns adult / alerts -----------------------------------------------------------------------------------

    @Test
    void theStatusGivesTheDateAndTheDaysLeft() {
        AgeStatus status = rules().status(LocalDate.of(2008, 12, 7), NOTHING);
        assertThat(status.minor()).isTrue();
        assertThat(status.turnsAdultOn()).isEqualTo(LocalDate.of(2026, 12, 7));
        assertThat(status.daysUntilAdult()).isEqualTo(60);
    }

    @Test
    void theAlertStartsAtSixtyDaysAndNotBefore() {
        assertThat(rules().status(LocalDate.of(2008, 12, 7), NOTHING).alert()).isEqualTo(AgeAlert.TURNS_ADULT_SOON);   // 60 days
        assertThat(rules().status(LocalDate.of(2008, 12, 8), NOTHING).alert()).isEqualTo(AgeAlert.NONE);               // 61 days
        assertThat(rules().status(LocalDate.of(2008, 10, 9), NOTHING).alert()).isEqualTo(AgeAlert.TURNS_ADULT_SOON);   // tomorrow
        assertThat(rules().status(LocalDate.of(2010, 1, 1), NOTHING).alert()).isEqualTo(AgeAlert.NONE);
    }

    @Test
    void anAdultWhoOnlyHasTheGuardiansAuthorizationNeedsANewOne() {
        AgeStatus status = rules().status(LocalDate.of(2007, 1, 1), Set.of(ConsentType.DATA_GUARDIAN));
        assertThat(status.minor()).isFalse();
        assertThat(status.turnedAdult()).isTrue();
        assertThat(status.alert()).isEqualTo(AgeAlert.TURNED_ADULT_NEEDS_AUTHORIZATION);
        assertThat(status.daysUntilAdult()).isZero();
    }

    @Test
    void noAlertOnceTheStudentAuthorizedOrWasNeverAMinorOnFile() {
        assertThat(rules().status(LocalDate.of(2007, 1, 1), Set.of(ConsentType.DATA_GUARDIAN, ConsentType.DATA_ADULT)).alert())
                .isEqualTo(AgeAlert.NONE);
        assertThat(rules().status(LocalDate.of(1995, 1, 1), Set.of(ConsentType.DATA_ADULT)).alert()).isEqualTo(AgeAlert.NONE);
        assertThat(rules().status(LocalDate.of(1995, 1, 1), NOTHING).alert()).isEqualTo(AgeAlert.NONE);
    }
}
