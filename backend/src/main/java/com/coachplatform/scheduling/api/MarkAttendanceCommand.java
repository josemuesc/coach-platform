package com.coachplatform.scheduling.api;

import jakarta.validation.constraints.NotNull;

public record MarkAttendanceCommand(@NotNull SessionStatus result) {
}
