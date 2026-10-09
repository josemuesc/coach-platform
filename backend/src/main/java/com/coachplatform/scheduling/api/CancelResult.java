package com.coachplatform.scheduling.api;

import io.swagger.v3.oas.annotations.media.Schema;
/** The cancelled place and, when a new date was given, the place that replaces it (null otherwise). */
public record CancelResult(AttendanceView cancelled, @Schema(nullable = true) AttendanceView replacement) {
}
