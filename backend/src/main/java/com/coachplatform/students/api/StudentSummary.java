package com.coachplatform.students.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * @param audience GUARDIAN while the student is under 18; null only for a student created before the birth date existed
 * @param ageAlert TURNS_ADULT_SOON (60 days or fewer) or TURNED_ADULT_NEEDS_AUTHORIZATION
 * @param anonymizationRequested a data authorization was revoked: the account is suspended and marked for anonymization
 */
public record StudentSummary(UUID id, String fullName, String email, String whatsappPhone, boolean active, boolean hasAccount,
                             String goal, LocalDate birthDate, GuardianView guardian, Audience audience, boolean minor,
                             LocalDate turnsAdultOn, long daysUntilAdult, AgeAlert ageAlert, boolean anonymizationRequested) {
}
