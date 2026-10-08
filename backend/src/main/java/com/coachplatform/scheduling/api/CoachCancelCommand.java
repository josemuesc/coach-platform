package com.coachplatform.scheduling.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** The reason is mandatory. newStartsAt is optional and, like the student's, atomic with the cancellation. */
public record CoachCancelCommand(@NotBlank @Size(max = 500) String reason, Instant newStartsAt) {
}
