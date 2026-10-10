package com.coachplatform.scheduling.domain;

import com.coachplatform.billing.api.Modality;
import com.coachplatform.scheduling.domain.SlotCalendar.Range;
import java.util.List;
import java.util.Set;
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

    /**
     * A slot as the COACH sees it for one student. needsOverride = it can only be taken with the coach's override, and blockedBy says
     * which rule (MODALITY_MISMATCH, EVENT_FULL or SLOT_TAKEN). eventId is null for a block that has no event yet.
     */
    public record CoachSlot(Range range, Modality modality, int capacity, int occupied, UUID eventId, boolean needsOverride,
                            SchedulingRuleException.Code blockedBy) {
    }

    /**
     * Same grid as the student's, but a slot that only an override could take is listed too, with the rule it breaks. Left out:
     * a slot overlapping an event of another length or several events (not even an override helps), and an event the student is
     * already in. The decision stays with {@link BookingRules}: this only mirrors its placement rules to LIST the options.
     */
    public static List<CoachSlot> forCoach(Modality studentModality, int defaultGroupCapacity, List<Range> gridSlots,
                                           List<EventInfo> events, Set<UUID> studentEventIds) {
        return gridSlots.stream().map(slot -> {
            List<EventInfo> overlapping = events.stream().filter(e -> e.range().overlaps(slot)).toList();
            if (overlapping.isEmpty()) {
                return new CoachSlot(slot, studentModality, BookingRules.capacityForNewEvent(studentModality, defaultGroupCapacity), 0,
                        null, false, null);
            }
            EventInfo event = overlapping.get(0);
            if (overlapping.size() > 1 || !event.range().equals(slot) || studentEventIds.contains(event.id())) {
                return null;
            }
            SchedulingRuleException.Code violation = null;
            if (event.modality() != studentModality) {
                violation = SchedulingRuleException.Code.MODALITY_MISMATCH;
            } else if (event.occupied() >= event.capacity()) {
                violation = studentModality == Modality.PERSONALIZED ? SchedulingRuleException.Code.SLOT_TAKEN
                        : SchedulingRuleException.Code.EVENT_FULL;
            }
            return new CoachSlot(slot, event.modality(), event.capacity(), event.occupied(), event.id(), violation != null, violation);
        }).filter(java.util.Objects::nonNull).toList();
    }
}
