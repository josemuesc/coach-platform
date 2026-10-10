package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.domain.SchedulingTestSupport.local;
import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.billing.api.Modality;
import com.coachplatform.scheduling.domain.SlotCalendar.Range;
import com.coachplatform.scheduling.domain.SlotCatalog.EventInfo;
import com.coachplatform.scheduling.domain.SlotCatalog.StudentSlot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.coachplatform.scheduling.domain.SchedulingRuleException.Code;
import com.coachplatform.scheduling.domain.SlotCatalog.CoachSlot;
import org.junit.jupiter.api.Test;

class SlotCatalogTest {

    private static final Duration HOUR = Duration.ofMinutes(60);

    private static Range range(String startLocal) {
        Instant s = local(startLocal);
        return new Range(s, s.plus(HOUR));
    }

    private static final List<Range> GRID = List.of(range("2026-10-13T08:00"), range("2026-10-13T09:00"), range("2026-10-13T10:00"), range("2026-10-13T11:00"));

    private static EventInfo event(String start, Modality modality, int capacity, int occupied) {
        return new EventInfo(UUID.randomUUID(), range(start), modality, capacity, occupied);
    }

    private static List<Instant> starts(List<StudentSlot> slots) {
        return slots.stream().map(s -> s.range().start()).toList();
    }

    @Test
    void aPersonalizedStudentOnlySeesBlocksNobodyHolds() {
        var events = List.of(
                event("2026-10-13T09:00", Modality.PERSONALIZED, 1, 1),
                event("2026-10-13T10:00", Modality.SEMI_PERSONALIZED, 4, 1),       // free seats, but not for his plan
                event("2026-10-13T11:00", Modality.SEMI_PERSONALIZED, 4, 4));

        var slots = SlotCatalog.forStudent(Modality.PERSONALIZED, 4, GRID, events);

        assertThat(starts(slots)).containsExactly(local("2026-10-13T08:00"));
        assertThat(slots.get(0)).satisfies(s -> {
            assertThat(s.modality()).isEqualTo(Modality.PERSONALIZED);
            assertThat(s.capacity()).isEqualTo(1);
            assertThat(s.occupied()).isZero();
            assertThat(s.eventId()).isNull();
        });
    }

    @Test
    void aSemiPersonalizedStudentSeesEmptyBlocksAndSemiEventsWithRoom() {
        var withRoom = event("2026-10-13T09:00", Modality.SEMI_PERSONALIZED, 4, 2);
        var events = List.of(
                withRoom,
                event("2026-10-13T10:00", Modality.PERSONALIZED, 1, 1),            // other modality
                event("2026-10-13T11:00", Modality.SEMI_PERSONALIZED, 3, 3));      // full

        var slots = SlotCatalog.forStudent(Modality.SEMI_PERSONALIZED, 4, GRID, events);

        assertThat(starts(slots)).containsExactly(local("2026-10-13T08:00"), local("2026-10-13T09:00"));
        assertThat(slots.get(0).modality()).isEqualTo(Modality.SEMI_PERSONALIZED);
        assertThat(slots.get(0).capacity()).as("an empty block would get the default capacity").isEqualTo(4);
        assertThat(slots.get(0).occupied()).isZero();
        assertThat(slots.get(1).occupied()).isEqualTo(2);
        assertThat(slots.get(1).capacity()).isEqualTo(4);
        assertThat(slots.get(1).eventId()).isEqualTo(withRoom.id());
    }

    @Test
    void anEmptyBlockShowsTheCurrentDefaultButExistingEventsKeepTheirOwnCapacity() {
        var existing = event("2026-10-13T09:00", Modality.SEMI_PERSONALIZED, 4, 1);   // created back when the default was 4

        var slots = SlotCatalog.forStudent(Modality.SEMI_PERSONALIZED, 6, GRID, List.of(existing));   // the default is 6 now

        assertThat(slots.get(0).capacity()).isEqualTo(6);                              // the empty 08:00 block
        assertThat(slots.get(1).capacity()).isEqualTo(4);                              // the existing event is unchanged
    }

    @Test
    void anEventOfAnotherLengthOrTwoEventsHideTheSlot() {
        var olderLongerEvent = new EventInfo(UUID.randomUUID(), new Range(local("2026-10-13T08:30"), local("2026-10-13T09:30")),
                Modality.SEMI_PERSONALIZED, 4, 1);   // overlaps the 08:00 and 09:00 slots without matching either

        var slots = SlotCatalog.forStudent(Modality.SEMI_PERSONALIZED, 4, GRID, List.of(olderLongerEvent));

        assertThat(starts(slots)).containsExactly(local("2026-10-13T10:00"), local("2026-10-13T11:00"));
    }

    @Test
    void noSlotsAtAllWhenTheGridIsEmpty() {
        assertThat(SlotCatalog.forStudent(Modality.PERSONALIZED, 4, List.of(), List.of())).isEmpty();
    }

    // ----------------------------------------------------------------- the coach's view for one student

    @Test
    void theCoachSeesEverySlotAStudentCouldTakeAndTheOnesOnlyAnOverrideCouldTakeWithTheirRule() {
        var full = event("2026-10-13T09:00", Modality.SEMI_PERSONALIZED, 4, 4);
        var otherModality = event("2026-10-13T10:00", Modality.PERSONALIZED, 1, 1);
        var open = event("2026-10-13T11:00", Modality.SEMI_PERSONALIZED, 4, 1);

        List<CoachSlot> slots = SlotCatalog.forCoach(Modality.SEMI_PERSONALIZED, 4, GRID, List.of(full, otherModality, open), Set.of());

        assertThat(slots).extracting(s -> s.range().start()).containsExactly(local("2026-10-13T08:00"), local("2026-10-13T09:00"),
                local("2026-10-13T10:00"), local("2026-10-13T11:00"));
        assertThat(slots).extracting(CoachSlot::needsOverride).containsExactly(false, true, true, false);
        assertThat(slots).extracting(CoachSlot::blockedBy).containsExactly(null, Code.EVENT_FULL, Code.MODALITY_MISMATCH, null);
        assertThat(slots.get(0).capacity()).as("an empty block takes the default group capacity").isEqualTo(4);
        assertThat(slots.get(3).occupied()).isEqualTo(1);
    }

    @Test
    void aPersonalizedStudentFindsATakenPersonalizedBlockAsSlotTakenWhichAnOverrideCanTake() {
        var taken = event("2026-10-13T09:00", Modality.PERSONALIZED, 1, 1);
        List<CoachSlot> slots = SlotCatalog.forCoach(Modality.PERSONALIZED, 4, List.of(range("2026-10-13T09:00")), List.of(taken), Set.of());
        assertThat(slots).singleElement().satisfies(s -> {
            assertThat(s.needsOverride()).isTrue();
            assertThat(s.blockedBy()).isEqualTo(Code.SLOT_TAKEN);
        });
    }

    @Test
    void anEventTheStudentIsAlreadyInAndOverlapsNoOverrideCanTakeAreNotListed() {
        var mine = event("2026-10-13T09:00", Modality.SEMI_PERSONALIZED, 4, 2);
        var longer = new EventInfo(UUID.randomUUID(), new Range(local("2026-10-13T10:00"), local("2026-10-13T11:30")), Modality.SEMI_PERSONALIZED, 4, 1);

        List<CoachSlot> slots = SlotCatalog.forCoach(Modality.SEMI_PERSONALIZED, 4, GRID, List.of(mine, longer), Set.of(mine.id()));

        assertThat(slots).extracting(s -> s.range().start()).containsExactly(local("2026-10-13T08:00"));
    }
}
