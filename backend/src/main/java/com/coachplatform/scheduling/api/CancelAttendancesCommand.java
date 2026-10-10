package com.coachplatform.scheduling.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Several of ONE student's booked classes, cancelled by the coach in a single all-or-nothing operation. The reason is mandatory. */
public record CancelAttendancesCommand(@NotEmpty @Size(max = 50) List<@NotNull UUID> attendanceIds, @NotBlank @Size(max = 500) String reason) {
}
