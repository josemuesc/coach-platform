package com.coachplatform.scheduling.api;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record BookSessionCommand(@NotNull Instant startsAt) {
}
