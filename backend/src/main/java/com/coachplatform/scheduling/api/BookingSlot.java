package com.coachplatform.scheduling.api;

import com.coachplatform.billing.api.Modality;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * A slot the coach can book for the student: as is (needsOverride = false) or only as an exception (blockedBy says which rule).
 * modality / capacity / occupied are those of the event when there is one, else the student's own with the default capacity.
 */
public record BookingSlot(Instant startsAt, Instant endsAt, String localTime, Modality modality, int capacity, int occupied,
                          @Schema(nullable = true) UUID eventId, boolean needsOverride, @Schema(nullable = true) OverrideRule blockedBy) {
}
