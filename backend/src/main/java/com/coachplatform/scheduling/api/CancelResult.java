package com.coachplatform.scheduling.api;

/** The cancelled class and, when a new date was given, the class that replaces it (null otherwise). */
public record CancelResult(SessionSummary cancelled, SessionSummary replacement) {
}
