package com.coachplatform.scheduling.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record MarkItem(@NotNull UUID attendanceId, @NotNull AttendanceStatus status) {
}
