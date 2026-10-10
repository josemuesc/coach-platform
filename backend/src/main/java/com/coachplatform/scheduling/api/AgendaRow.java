package com.coachplatform.scheduling.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of a day of the agenda, in time order. EVENT carries {@code event}; BLOCK carries {@code blockId}/{@code blockReason}, clipped
 * to the day ({@code allDay}: it covers the whole local day); FREE is a bookable slot nobody holds. {@code localTime} is the start in the
 * coach's clock ("HH:mm", America/Bogota).
 */
public record AgendaRow(AgendaRowKind kind, Instant startsAt, Instant endsAt, String localTime, @Schema(nullable = true) EventView event,
                         @Schema(nullable = true) UUID blockId, @Schema(nullable = true) String blockReason, boolean allDay) {
}
