package com.coachplatform.students.domain;

import static com.coachplatform.students.domain.StudentRuleException.Code.AUDIENCE_CHANGE_BLOCKED;
import static com.coachplatform.students.domain.StudentRuleException.Code.GUARDIAN_INCOMPLETE;
import static com.coachplatform.students.domain.StudentRuleException.Code.GUARDIAN_REQUIRED;
import static com.coachplatform.students.domain.StudentRuleException.Code.INVALID_BIRTH_DATE;

import com.coachplatform.students.api.Audience;
import com.coachplatform.students.api.ConsentType;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * Minors and their legal guardian. Age is always computed on the calendar day in America/Bogota (never the UTC day) and
 * only the birth date is stored, never the age.
 *
 * <p>For the pilot the guardian IS the account holder of a minor: the invitation goes to the guardian's email, the
 * guardian accepts the guardian authorization and receives the notices. The minor has no account of their own.
 */
public final class GuardianRules {

    public static final int ADULT_AGE = 18;
    public static final int ADULT_SOON_DAYS = 60;
    private static final LocalDate EARLIEST_BIRTH_DATE = LocalDate.of(1900, 1, 1);
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    /** Alert shown to the coach about the age of a student. */
    public enum AgeAlert {
        NONE,
        /** A minor who turns 18 in {@link #ADULT_SOON_DAYS} days or fewer. */
        TURNS_ADULT_SOON,
        /** Already 18 but the guardian is still the holder: a new authorization from the student is due. */
        TURNED_ADULT_NEEDS_AUTHORIZATION
    }

    public record AgeStatus(boolean minor, LocalDate turnsAdultOn, long daysUntilAdult, boolean turnedAdult, AgeAlert alert) {
    }

    private final Clock clock;

    public GuardianRules(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(BOGOTA));
    }

    /** A real, past birth date. Mandatory: the consent modality depends on it. */
    public void requireValidBirthDate(LocalDate birthDate) {
        if (birthDate == null) {
            throw new StudentRuleException(INVALID_BIRTH_DATE, "The birth date is required");
        }
        if (birthDate.isAfter(today()) || birthDate.isBefore(EARLIEST_BIRTH_DATE)) {
            throw new StudentRuleException(INVALID_BIRTH_DATE, "The birth date is not valid");
        }
    }

    /**
     * The first day the student is an adult. A person born on 29 February reaches 18 on 1 March in a year with no 29th:
     * the later date is the conservative one (a day more as a minor, never a day less).
     */
    public LocalDate turnsAdultOn(LocalDate birthDate) {
        LocalDate eighteenth = birthDate.plusYears(ADULT_AGE);
        if (birthDate.getMonth() == Month.FEBRUARY && birthDate.getDayOfMonth() == 29 && eighteenth.getDayOfMonth() == 28) {
            return eighteenth.plusDays(1);
        }
        return eighteenth;
    }

    public boolean isMinor(LocalDate birthDate) {
        return today().isBefore(turnsAdultOn(birthDate));
    }

    public Audience audienceFor(LocalDate birthDate) {
        return isMinor(birthDate) ? Audience.GUARDIAN : Audience.ADULT;
    }

    /** The data authorization that has to be accepted for this student today. */
    public ConsentType requiredDataConsent(LocalDate birthDate) {
        return isMinor(birthDate) ? ConsentType.DATA_GUARDIAN : ConsentType.DATA_ADULT;
    }

    /**
     * Guardian data must be all-or-nothing, and complete for a minor. An adult may have it too (their parent paying) but
     * it is never required.
     */
    public void requireGuardianData(LocalDate birthDate, GuardianData guardian) {
        if (!guardian.isEmpty() && !guardian.isComplete()) {
            throw new StudentRuleException(GUARDIAN_INCOMPLETE,
                    "The guardian's name, relationship, phone and email must be given together");
        }
        if (isMinor(birthDate) && !guardian.isComplete()) {
            throw new StudentRuleException(GUARDIAN_REQUIRED,
                    "A student under 18 needs the guardian's name, relationship, phone and email");
        }
    }

    /**
     * Once an invitation has been accepted, the birth date cannot be edited to move the student between the adult and the
     * minor modality (either direction): the authorization already on file would no longer match the person who gave it.
     */
    public void requireAudienceChangeAllowed(LocalDate oldBirthDate, LocalDate newBirthDate, boolean invitationAccepted) {
        if (invitationAccepted && audienceFor(oldBirthDate) != audienceFor(newBirthDate)) {
            throw new StudentRuleException(AUDIENCE_CHANGE_BLOCKED,
                    "The birth date cannot move this student between minor and adult once the invitation was accepted");
        }
    }

    /** The guardian holds the account while the student is a minor, and after turning 18 until they authorize on their own. */
    public boolean guardianIsHolder(LocalDate birthDate, Set<ConsentType> accepted) {
        if (isMinor(birthDate)) {
            return true;
        }
        return accepted.contains(ConsentType.DATA_GUARDIAN) && !accepted.contains(ConsentType.DATA_ADULT);
    }

    /** The login / invitation email: the guardian's while the guardian is the holder, otherwise the student's own. */
    public String accessEmail(LocalDate birthDate, Set<ConsentType> accepted, String studentEmail, GuardianData guardian) {
        return guardianIsHolder(birthDate, accepted) && guardian.email() != null ? guardian.email() : studentEmail;
    }

    /** Where notices go: the guardian's phone while the guardian is the holder, otherwise the student's WhatsApp. */
    public String notificationPhone(LocalDate birthDate, Set<ConsentType> accepted, String studentPhone, GuardianData guardian) {
        return guardianIsHolder(birthDate, accepted) && guardian.phone() != null ? guardian.phone() : studentPhone;
    }

    public AgeStatus status(LocalDate birthDate, Set<ConsentType> accepted) {
        LocalDate adultOn = turnsAdultOn(birthDate);
        boolean minor = today().isBefore(adultOn);
        long days = minor ? ChronoUnit.DAYS.between(today(), adultOn) : 0;
        boolean turnedAdult = !minor && accepted.contains(ConsentType.DATA_GUARDIAN) && !accepted.contains(ConsentType.DATA_ADULT);
        AgeAlert alert = turnedAdult ? AgeAlert.TURNED_ADULT_NEEDS_AUTHORIZATION
                : minor && days <= ADULT_SOON_DAYS ? AgeAlert.TURNS_ADULT_SOON : AgeAlert.NONE;
        return new AgeStatus(minor, adultOn, days, turnedAdult, alert);
    }
}
