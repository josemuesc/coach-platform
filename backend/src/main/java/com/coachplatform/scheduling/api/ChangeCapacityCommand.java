package com.coachplatform.scheduling.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ChangeCapacityCommand(@NotNull @Min(2) @Max(10) Integer capacity) {
}
