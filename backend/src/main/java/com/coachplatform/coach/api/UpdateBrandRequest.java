package com.coachplatform.coach.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Both fields are mandatory (the PUT replaces the brand). The color is {@code #RRGGBB}; it is validated here, never by the client. */
public record UpdateBrandRequest(
        @NotBlank @Size(max = 100) String brandName,
        @NotNull @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String primaryColor) {
}
