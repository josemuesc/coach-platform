package com.coachplatform.scheduling.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Cancels ONE student's place. The reason is mandatory. newStartsAt is optional and atomic; override (optional, defaults to
 * false) applies to that new place.
 */
public record CoachCancelCommand(@NotBlank @Size(max = 500) String reason, Instant newStartsAt, Boolean override,
                                 String overrideReason) {

    public CoachCancelCommand {
        if (override == null) {
            override = Boolean.FALSE;
        }
    }
}
