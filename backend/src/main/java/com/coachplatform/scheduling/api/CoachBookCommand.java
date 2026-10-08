package com.coachplatform.scheduling.api;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * override (optional, defaults to false) = put the student in a full event or one of another modality. It needs a reason and
 * is audited. Only the coach can.
 */
public record CoachBookCommand(@NotNull Instant startsAt, Boolean override, String overrideReason) {

    public CoachBookCommand {
        if (override == null) {
            override = Boolean.FALSE;
        }
    }
}
