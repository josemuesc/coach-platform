package com.coachplatform.students.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * The birth date is mandatory (the consent modality depends on it) and only the date is stored, never the age. The email
 * is the student's login and is stored in lowercase; for a student under 18 it is ignored and replaced by the guardian's
 * email (the guardian holds the account). The guardian is required for a student under 18.
 */
public record StudentInput(
        @NotBlank @Size(max = 200) String fullName,
        @Email @Size(max = 320) String email,
        @Size(max = 30) String whatsappPhone,
        @NotNull LocalDate birthDate,
        @Size(max = 500) String goal,
        @Valid GuardianInput guardian) {
}
