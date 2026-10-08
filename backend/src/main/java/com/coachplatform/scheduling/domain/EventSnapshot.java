package com.coachplatform.scheduling.domain;

import com.coachplatform.billing.api.Modality;
import com.coachplatform.scheduling.domain.SlotCalendar.Range;

/**
 * An existing SCHEDULED event as a booking sees it.
 * occupied = live places (booked or already seen); it can exceed capacity after a coach override.
 * studentAlreadyIn = the student asking already holds a live place in this very event.
 */
public record EventSnapshot(Range range, Modality modality, int capacity, int occupied, boolean studentAlreadyIn) {
}
