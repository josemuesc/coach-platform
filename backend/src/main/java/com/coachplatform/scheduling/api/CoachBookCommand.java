package com.coachplatform.scheduling.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * override (optional, defaults to false) = put the student in a full event, one of another modality or at a time outside the weekly
 * schedule (on a quarter hour). It needs a reason and is audited. Only the coach can.
 */
public record CoachBookCommand(@NotNull Instant startsAt, Boolean override, @Size(max = 200) String overrideReason) {

    public CoachBookCommand {
        if (override == null) {
            override = Boolean.FALSE;
        }
    }
}
