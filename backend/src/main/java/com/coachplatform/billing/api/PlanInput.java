package com.coachplatform.billing.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Price in whole Colombian pesos (COP), no decimals. EVERY field is mandatory: the numbers are wrappers so that an absent one
 * is a validation error (400) instead of silently becoming 0, and a plan is either personalized or semi-personalized.
 */
public record PlanInput(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Min(1) @Max(200) Integer classesIncluded,
        @NotNull @Min(0) Long priceCop,
        @NotNull Modality modality) {
}
