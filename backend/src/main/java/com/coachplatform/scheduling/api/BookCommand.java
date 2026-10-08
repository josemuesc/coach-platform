package com.coachplatform.scheduling.api;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** A student asks for a time. The server decides whether that creates an event or joins one, by modality and capacity. */
public record BookCommand(@NotNull Instant startsAt) {
}
