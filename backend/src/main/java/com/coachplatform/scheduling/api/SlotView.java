package com.coachplatform.scheduling.api;

import java.time.Instant;

/** localDate / localTime are the coach's wall-clock (America/Bogota) so a client need not convert. */
public record SlotView(Instant startsAt, Instant endsAt, String localDate, String localTime) {
}
