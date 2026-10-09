package com.coachplatform.students.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

/**
 * @param audience GUARDIAN while the student is under 18; null only for a student created before the birth date existed
 * @param ageAlert TURNS_ADULT_SOON (60 days or fewer) or TURNED_ADULT_NEEDS_AUTHORIZATION
 * @param anonymizationRequested a data authorization was revoked: the account is suspended and marked for anonymization
 */
public record StudentSummary(UUID id, String fullName, String email, @Schema(nullable = true) String whatsappPhone, boolean active, boolean hasAccount,
                             @Schema(nullable = true) String goal, @Schema(nullable = true) LocalDate birthDate, @Schema(nullable = true) GuardianView guardian, @Schema(nullable = true) Audience audience, boolean minor,
                             @Schema(nullable = true) LocalDate turnsAdultOn, long daysUntilAdult, @Schema(nullable = true) AgeAlert ageAlert, boolean anonymizationRequested) {
}
