package com.coachplatform.scheduling.api;

import com.coachplatform.billing.api.Modality;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What can be booked for one student on one day, all decided by the server. Without an active cycle or classes left, canBook is false,
 * blockedReason says why and slots is empty. dayWindows = the weekly schedule of that weekday (information for an off-schedule class).
 */
public record BookingOptions(UUID studentId, LocalDate localDate, @Schema(nullable = true) Modality modality, int classesAvailable,
                             boolean canBook, @Schema(nullable = true) BookBlockedReason blockedReason,
                             @Schema(nullable = true) LocalDate cycleEndDate, List<WindowView> dayWindows, List<BookingSlot> slots) {
}
