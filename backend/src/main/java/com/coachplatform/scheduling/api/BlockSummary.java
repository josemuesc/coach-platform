package com.coachplatform.scheduling.api;

import java.time.Instant;
import java.util.UUID;

public record BlockSummary(UUID id, Instant startsAt, Instant endsAt, String reason) {
}
