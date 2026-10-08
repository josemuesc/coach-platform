package com.coachplatform.billing.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Price in whole Colombian pesos (COP), no decimals. */
public record PlanInput(
        @NotBlank @Size(max = 100) String name,
        @Min(1) @Max(200) int classesIncluded,
        @Min(0) long priceCop) {
}
