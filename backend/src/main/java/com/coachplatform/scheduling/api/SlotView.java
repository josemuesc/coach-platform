package com.coachplatform.scheduling.api;

import java.time.Instant;

/** An empty block of the coach (no event on it). localDate / localTime are the coach's wall-clock (America/Bogota). */
public record SlotView(Instant startsAt, Instant endsAt, String localDate, String localTime) {
}
