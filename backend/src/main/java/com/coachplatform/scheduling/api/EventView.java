package com.coachplatform.scheduling.api;

import com.coachplatform.billing.api.Modality;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An event with its live attendees (coach view). occupied can exceed capacity after an override; freeSeats never goes below 0. */
public record EventView(UUID id, Instant startsAt, Instant endsAt, Modality modality, int capacity, int occupied,
                        int freeSeats, EventStatus status, List<AttendeeView> attendees) {
}
