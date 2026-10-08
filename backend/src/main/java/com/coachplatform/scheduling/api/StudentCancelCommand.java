package com.coachplatform.scheduling.api;

import java.time.Instant;

/** newStartsAt is optional. With it the change is atomic: if the new slot is not valid, nothing is cancelled. */
public record StudentCancelCommand(Instant newStartsAt) {
}
