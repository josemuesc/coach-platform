package com.coachplatform.scheduling.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelEventCommand(@NotBlank @Size(max = 500) String reason) {
}
