package com.coachplatform.billing.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record ExtendCycleCommand(@NotNull LocalDate newEndDate, @NotBlank @Size(max = 500) String reason) {
}
