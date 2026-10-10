package com.coachplatform.scheduling.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A block in the coach's LOCAL time. allDay covers the whole day; otherwise startTime and endTime ("HH:mm") are required. The reason is
 * mandatory (it is also the reason of every class the block releases). affectedAttendanceIds (optional, empty by default; ignored by the
 * preview) = the classes the coach saw in the confirmation: if the real set differs when saving, nothing is saved (BLOCK_AFFECTED_CHANGED).
 */
public record BlockInput(@NotNull LocalDate localDate, @NotNull Boolean allDay, String startTime, String endTime,
                         @NotBlank @Size(max = 100) String reason, List<@NotNull UUID> affectedAttendanceIds) {

    public BlockInput {
        if (affectedAttendanceIds == null) {
            affectedAttendanceIds = List.of();
        }
    }
}
