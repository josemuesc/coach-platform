package com.coachplatform.scheduling.api;

import jakarta.validation.constraints.NotNull;

public record MarkCommand(@NotNull AttendanceStatus result) {
}
