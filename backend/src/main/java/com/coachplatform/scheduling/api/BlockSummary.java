package com.coachplatform.scheduling.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** localDate / startTime / endTime are the coach's wall clock; startTime and endTime are null for an all-day block. */
public record BlockSummary(UUID id, Instant startsAt, Instant endsAt, String localDate, boolean allDay,
                           @Schema(nullable = true) String startTime, @Schema(nullable = true) String endTime,
                           @Schema(nullable = true) String reason) {
}
