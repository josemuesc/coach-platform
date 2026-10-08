package com.coachplatform.scheduling.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record BlockInput(@NotNull Instant startsAt, @NotNull Instant endsAt, @Size(max = 200) String reason) {
}
