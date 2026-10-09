package com.coachplatform.scheduling.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record BlockSummary(UUID id, Instant startsAt, Instant endsAt, @Schema(nullable = true) String reason) {
}
