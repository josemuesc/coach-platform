package com.coachplatform.scheduling.domain;

import com.coachplatform.billing.api.Modality;
import com.coachplatform.scheduling.domain.SlotCalendar.Range;
import java.util.List;
import java.util.UUID;

/**
 * What a STUDENT is offered: each slot with its modality and "occupied of capacity", already filtered by what the
 * student's plan allows. A personalized student only sees blocks nobody holds; a semi-personalized student sees blocks
 * nobody holds and semi-personalized events that still have a seat. Other students are never identified.
 */
public final class SlotCatalog {

    /** eventId is null for a block that has no event yet. */
    public record StudentSlot(Range range, Modality modality, int capacity, int occupied, UUID eventId) {
    }

    /** An existing SCHEDULED event, as listed to the catalog. */
    public record EventInfo(UUID id, Range range, Modality modality, int capacity, int occupied) {
    }

    private SlotCatalog() {
    }

    /**
     * @param gridSlots the coach's slots already filtered for the student (future, lead time, not blocked)
     * @param events    the coach's SCHEDULED events in the same period
     */
    public static List<StudentSlot> forStudent(Modality studentModality, int defaultGroupCapacity, List<Range> gridSlots,
                                               List<EventInfo> events) {
        return gridSlots.stream().map(slot -> {
            List<EventInfo> overlapping = events.stream().filter(e -> e.range().overlaps(slot)).toList();
            if (overlapping.isEmpty()) {
                return new StudentSlot(slot, studentModality, BookingRules.capacityForNewEvent(studentModality, defaultGroupCapacity), 0, null);
            }
            EventInfo event = overlapping.get(0);
            boolean joinable = overlapping.size() == 1 && event.range().equals(slot)
                    && event.modality() == studentModality && event.occupied() < event.capacity();
            return joinable ? new StudentSlot(slot, event.modality(), event.capacity(), event.occupied(), event.id()) : null;
        }).filter(java.util.Objects::nonNull).toList();
    }
}
