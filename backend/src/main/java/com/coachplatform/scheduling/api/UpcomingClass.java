package com.coachplatform.scheduling.api;

import com.coachplatform.billing.api.Modality;
import java.time.Instant;
import java.util.UUID;

/** A booked class of the student that has not ended yet. {@code today} (America/Bogota) is decided here, never by the client. */
public record UpcomingClass(UUID attendanceId, UUID eventId, Instant startsAt, Instant endsAt, Modality modality, boolean today) {
}
