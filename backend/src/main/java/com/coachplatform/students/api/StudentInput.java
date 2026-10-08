package com.coachplatform.students.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Email is mandatory: it is the student's login. It is stored in lowercase. */
public record StudentInput(
        @NotBlank @Size(max = 200) String fullName,
        @NotBlank @Email @Size(max = 320) String email,
        @Size(max = 30) String whatsappPhone) {
}
