package com.coachplatform.scheduling.api;

/** The cancelled place and, when a new date was given, the place that replaces it (null otherwise). */
public record CancelResult(AttendanceView cancelled, AttendanceView replacement) {
}
