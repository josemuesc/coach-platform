package com.coachplatform.students.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** All four fields travel together (or none); a student under 18 needs them all. Rules are checked by the service. */
public record GuardianInput(
        @Size(max = 200) String name,
        @Size(max = 50) String relationship,
        @Size(max = 30) String phone,
        @Email @Size(max = 320) String email) {
}
