package com.coachplatform.scheduling.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.coachplatform.billing.api.Modality;
import java.time.Instant;
import java.util.UUID;

/** A block a student could take: its modality and "occupied of capacity", already filtered by what the student's plan allows. */
public record StudentSlotView(Instant startsAt, Instant endsAt, String localDate, String localTime, Modality modality,
                              int capacity, int occupied, @Schema(nullable = true) UUID eventId) {
}
