package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Taking a place in an event: creating it or joining it, by modality and capacity. */
class EventBookingTest extends SchedulingApiTest {

    private static final String DAY = "2026-10-12";
    private static final String TEN = at(DAY, "10:00");
    private static final String ELEVEN = at(DAY, "11:00");

    // ================================================================= personalized

    @Test
    void aPersonalizedStudentCreatesAnEventOfCapacityOne() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");

        List<String> modalities = JsonPath.read(studentSlots(ana, DAY, DAY), "$[*].modality");
        assertThat(modalities).hasSize(14).containsOnly("PERSONALIZED");
        assertThat(JsonPath.<List<Integer>>read(studentSlots(ana, DAY, DAY), "$[*].capacity")).containsOnly(1);
        assertThat(JsonPath.<List<Integer>>read(studentSlots(ana, DAY, DAY), "$[*].occupied")).containsOnly(0);

        studentBooks(ana, TEN).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.modality").value("PERSONALIZED"))
                .andExpect(jsonPath("$.capacity").value(1))
                .andExpect(jsonPath("$.occupied").value(1))
                .andExpect(jsonPath("$.startsAt").value(TEN))
                .andExpect(jsonPath("$.endsAt").value(at(DAY, "11:00")))
                .andExpect(jsonPath("$.studentName").doesNotExist());
        assertThat(JsonPath.<List<?>>read(studentSlots(ana, DAY, DAY), "$")).hasSize(13);
    }

    @Test
    void withoutAnActiveCycleThereAreNoSlotsAndNoBooking() throws Exception {
        var coach = newCoach(8);
        var ana = newStudent(coach, "Ana");   // never paid

        assertThat(JsonPath.<List<?>>read(studentSlots(ana, DAY, DAY), "$")).isEmpty();
        studentBooks(ana, TEN).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_ACTIVE_CYCLE"));
    }

    // ================================================================= semi-personalized

    @Test
    void severalSemiPersonalizedStudentsShareOneEvent() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");

        var first = studentBooked(ana, TEN);
        assertThat(JsonPath.<String>read(json(first), "$.modality")).isEqualTo("SEMI_PERSONALIZED");
        assertThat(JsonPath.<Integer>read(json(first), "$.capacity")).isEqualTo(4);   // the coach's default
        assertThat(JsonPath.<Integer>read(json(first), "$.occupied")).isEqualTo(1);

        // Beto is offered that very event, with "1 of 4"
        String slots = studentSlots(beto, DAY, DAY);
        assertThat(JsonPath.<List<String>>read(slots, "$[?(@.startsAt=='" + TEN + "')].eventId")).containsExactly(eventId(first));
        assertThat(JsonPath.<List<Integer>>read(slots, "$[?(@.startsAt=='" + TEN + "')].occupied")).containsExactly(1);
        assertThat(JsonPath.<List<Integer>>read(slots, "$[?(@.startsAt=='" + TEN + "')].capacity")).containsExactly(4);

        var second = studentBooked(beto, TEN);
        assertThat(eventId(second)).isEqualTo(eventId(first));
        assertThat(JsonPath.<Integer>read(json(second), "$.occupied")).isEqualTo(2);

        // the coach sees ONE event with both attendees and the free seats
        String agenda = agenda(coach, DAY, DAY);
        assertThat(JsonPath.<List<?>>read(agenda, "$.events")).hasSize(1);
        assertThat(JsonPath.<Integer>read(agenda, "$.events[0].occupied")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(agenda, "$.events[0].freeSeats")).isEqualTo(2);
        assertThat(JsonPath.<List<String>>read(agenda, "$.events[0].attendees[*].studentName")).containsExactlyInAnyOrder("Ana", "Beto");
        assertThat(JsonPath.<List<?>>read(agenda, "$.freeBlocks")).hasSize(13);   // that hour is no longer an empty block
    }

    @Test
    void theLastSeatGoesToOneStudentAndTheNextOneGetsEventFull() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        var carla = semi(coach, "Carla");
        String event = eventId(studentBooked(ana, TEN));
        changeCapacity(coach, event, 2).andExpect(status().isOk());

        studentBooks(beto, TEN).andExpect(status().isCreated());                              // takes the last seat
        studentBooks(carla, TEN).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_FULL"));
        // and a full event is not even offered
        assertThat(JsonPath.<List<?>>read(studentSlots(carla, DAY, DAY), "$[?(@.startsAt=='" + TEN + "')]")).isEmpty();
    }

    // ================================================================= the modality rules

    @Test
    void aPersonalizedStudentNeverJoinsAnotherStudentsEvent() throws Exception {
        var coach = newCoach(8);
        var carlos = personalized(coach, "Carlos");
        var pilar = personalized(coach, "Pilar");
        var ana = semi(coach, "Ana");
        studentBooked(ana, TEN);          // a semi event with free seats
        studentBooked(pilar, ELEVEN);     // a personalized event

        studentBooks(carlos, TEN).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MODALITY_MISMATCH"));
        studentBooks(carlos, ELEVEN).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_TAKEN"));
        // neither is offered to him: only blocks nobody holds
        assertThat(JsonPath.<List<String>>read(studentSlots(carlos, DAY, DAY), "$[*].startsAt")).hasSize(12).doesNotContain(TEN, ELEVEN);
    }

    @Test
    void aSemiPersonalizedStudentJoinsOnlySemiEventsOrEmptyBlocks() throws Exception {
        var coach = newCoach(8);
        var beto = semi(coach, "Beto");
        var ana = semi(coach, "Ana");
        var pilar = personalized(coach, "Pilar");
        studentBooked(ana, TEN);
        studentBooked(pilar, ELEVEN);

        studentBooks(beto, ELEVEN).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MODALITY_MISMATCH"));
        assertThat(JsonPath.<List<String>>read(studentSlots(beto, DAY, DAY), "$[*].startsAt"))
                .hasSize(13).contains(TEN).doesNotContain(ELEVEN);   // the semi event with room is offered, the personalized one is not
        studentBooks(beto, TEN).andExpect(status().isCreated());
    }

    // ================================================================= capacity: the coach's default and each event's own

    @Test
    void theDefaultGroupCapacityOnlyAffectsEventsCreatedAfterwards() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        var pilar = personalized(coach, "Pilar");

        putSettings(coach, 2, 60, 3, 200);
        String first = eventId(studentBooked(ana, TEN));
        putSettings(coach, 2, 60, 6, 200);                    // the default changes afterwards
        String second = eventId(studentBooked(beto, ELEVEN));
        String personalizedEvent = eventId(studentBooked(pilar, at(DAY, "12:00")));

        String agenda = agenda(coach, DAY, DAY);
        assertThat(JsonPath.<List<Integer>>read(agenda, "$.events[?(@.id=='" + first + "')].capacity")).containsExactly(3);   // unchanged
        assertThat(JsonPath.<List<Integer>>read(agenda, "$.events[?(@.id=='" + second + "')].capacity")).containsExactly(6);  // the new default
        assertThat(JsonPath.<List<Integer>>read(agenda, "$.events[?(@.id=='" + personalizedEvent + "')].capacity")).containsExactly(1);
    }

    @Test
    void theDefaultGroupCapacityMustBeBetweenTwoAndTen() throws Exception {
        var coach = newCoach(8);
        putSettings(coach, 2, 60, 1, 400);
        putSettings(coach, 2, 60, 0, 400);
        putSettings(coach, 2, 60, 11, 400);
        putSettings(coach, 2, 60, -2, 400);
        putSettings(coach, 2, 60, 2, 200);
        putSettings(coach, 2, 60, 10, 200);
        mvc.perform(withToken(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/coach/settings"), coach.token()))
                .andExpect(jsonPath("$.defaultGroupCapacity").value(10));
    }

    @Test
    void theCoachChangesOneEventsCapacityWithinTheRangeAndNeverBelowItsAttendees() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        var carla = semi(coach, "Carla");
        var dani = semi(coach, "Dani");
        String event = eventId(studentBooked(ana, TEN));
        studentBooked(beto, TEN);
        studentBooked(carla, TEN);

        changeCapacity(coach, event, 3).andExpect(status().isOk()).andExpect(jsonPath("$.capacity").value(3)).andExpect(jsonPath("$.freeSeats").value(0));
        studentBooks(dani, TEN).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_FULL"));

        // three people are inside: 2 is refused with a clear message
        changeCapacity(coach, event, 2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPACITY_BELOW_OCCUPANCY"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already has 3 attendee(s)")));
        // the range is 2-10
        changeCapacity(coach, event, 1).andExpect(status().isBadRequest());
        changeCapacity(coach, event, 11).andExpect(status().isBadRequest());

        // raising it lets the next student in
        changeCapacity(coach, event, 5).andExpect(status().isOk());
        studentBooks(dani, TEN).andExpect(status().isCreated());
    }

    @Test
    void aPersonalizedEventsCapacityIsNotConfigurableAndNoOneCanJoinIt() throws Exception {
        var coach = newCoach(8);
        var pilar = personalized(coach, "Pilar");
        String event = eventId(studentBooked(pilar, TEN));

        changeCapacity(coach, event, 2).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAPACITY_NOT_CONFIGURABLE"));
    }

    @Test
    void theCapacityCanOnlyChangeBeforeTheEventStarts() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        String event = eventId(studentBooked(ana, TEN));

        goTo(DAY, "09:59");
        changeCapacity(coach, event, 6).andExpect(status().isOk());
        goTo(DAY, "10:00");
        changeCapacity(coach, event, 7).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_ALREADY_STARTED"));
    }

    // ================================================================= the coach's override

    @Test
    void theCoachCanAddAStudentToAFullEventWithAReasonAndItShowsInTheAgenda() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        var carla = semi(coach, "Carla");
        String event = eventId(studentBooked(ana, TEN));
        studentBooked(beto, TEN);
        changeCapacity(coach, event, 2).andExpect(status().isOk());

        coachBooks(coach, carla, TEN, true, null).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        coachBooks(coach, carla, TEN, true, "Pidió quedarse a esta hora").andExpect(status().isCreated())
                .andExpect(jsonPath("$.override").value(true))
                .andExpect(jsonPath("$.overrideReason").value("Pidió quedarse a esta hora"));

        String agenda = agenda(coach, DAY, DAY);
        assertThat(JsonPath.<Integer>read(agenda, "$.events[0].occupied")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(agenda, "$.events[0].capacity")).isEqualTo(2);       // the event keeps its own capacity
        assertThat(JsonPath.<Integer>read(agenda, "$.events[0].freeSeats")).isZero();
        assertThat(JsonPath.<List<Boolean>>read(agenda, "$.events[0].attendees[?(@.studentName=='Carla')].override")).containsExactly(true);
        assertThat(JsonPath.<List<String>>read(agenda, "$.events[0].attendees[?(@.studentName=='Carla')].overrideReason"))
                .containsExactly("Pidió quedarse a esta hora");
        assertThat(JsonPath.<List<Boolean>>read(agenda, "$.events[0].attendees[?(@.studentName=='Ana')].override")).containsExactly(false);
    }

    @Test
    void theCoachCanAddAStudentToAnEventOfTheOtherModality() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var carlos = personalized(coach, "Carlos");
        studentBooked(ana, TEN);

        coachBooks(coach, carlos, TEN, false, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MODALITY_MISMATCH"));
        coachBooks(coach, carlos, TEN, true, "Caso especial").andExpect(status().isCreated()).andExpect(jsonPath("$.override").value(true));
    }

    @Test
    void anOverrideThatIsNotNeededIsNotRecorded() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        coachBooks(coach, ana, TEN, true, "por si acaso").andExpect(status().isCreated()).andExpect(jsonPath("$.override").value(false));
    }

    @Test
    void anOverrideNeverRelaxesTheQuotaTheCycleOrTheCalendar() throws Exception {
        var coach = newCoach(1);
        var ana = semi(coach, "Ana");        // a plan of ONE class
        var beto = semi(coach, "Beto");
        studentBooked(ana, TEN);
        studentBooked(beto, ELEVEN);
        changeCapacity(coach, eventIdAt(coach, ELEVEN), 2).andExpect(status().isOk());

        coachBooks(coach, ana, ELEVEN, true, "forzar").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"));
        coachBooks(coach, beto, at(DAY, "21:00"), true, "forzar").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NOT_AVAILABLE"));
        coachBooks(coach, beto, at("2026-11-09", "10:00"), true, "forzar").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aStudentCanNeverOverrideEvenIfTheyAskForIt() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        var carla = semi(coach, "Carla");
        String event = eventId(studentBooked(ana, TEN));
        studentBooked(beto, TEN);
        changeCapacity(coach, event, 2).andExpect(status().isOk());

        mvc.perform(withToken(post("/api/student/sessions"), carla.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startsAt\":\"" + TEN + "\",\"override\":true,\"overrideReason\":\"dejame entrar\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_FULL"));
    }

    // ================================================================= cancelling ONE student's place

    @Test
    void cancellingOnePlaceLeavesTheEventRunningForTheOthersAndTheLastOneCancelsIt() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        String anaPlace = attendanceId(studentBooked(ana, TEN));
        String betoPlace = attendanceId(studentBooked(beto, TEN));

        studentCancels(ana, anaPlace, null).andExpect(status().isOk()).andExpect(jsonPath("$.cancelled.status").value("CANCELLED_ON_TIME"));
        String agenda = agenda(coach, DAY, DAY);
        assertThat(JsonPath.<List<?>>read(agenda, "$.events")).hasSize(1);               // the event goes on
        assertThat(JsonPath.<Integer>read(agenda, "$.events[0].occupied")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(agenda, "$.events[0].attendees[*].studentName")).containsExactly("Beto");
        assertThat(classesUsed(coach, ana)).isZero();

        studentCancels(beto, betoPlace, null).andExpect(status().isOk());
        assertThat(JsonPath.<List<?>>read(agenda(coach, DAY, DAY), "$.events")).isEmpty();   // no live place left: the event is cancelled
        assertThat(JsonPath.<List<?>>read(agenda(coach, DAY, DAY), "$.freeBlocks")).hasSize(14);
    }

    @Test
    void aCancelledEventFreesItsTimeForAnotherReservationEvenOfAnotherModality() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var carlos = personalized(coach, "Carlos");
        String anaPlace = attendanceId(studentBooked(ana, TEN));
        studentBooks(carlos, TEN).andExpect(status().isConflict());                       // taken by the semi event

        studentCancels(ana, anaPlace, null).andExpect(status().isOk());                   // empties it: the event is cancelled

        studentBooks(carlos, TEN).andExpect(status().isCreated())                         // the same time is free again
                .andExpect(jsonPath("$.modality").value("PERSONALIZED")).andExpect(jsonPath("$.capacity").value(1));
    }

    @Test
    void movingToAnotherTimeJoinsAnExistingEventOrCreatesANewOneAtomically() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var dani = semi(coach, "Dani");
        String anaPlace = attendanceId(studentBooked(ana, TEN));
        String daniEvent = eventId(studentBooked(dani, at(DAY, "15:00")));

        var moved = studentCancels(ana, anaPlace, at(DAY, "15:00")).andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled.status").value("RESCHEDULED"))
                .andExpect(jsonPath("$.replacement.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.replacement.rescheduledFrom").value(anaPlace))
                .andExpect(jsonPath("$.replacement.occupied").value(2)).andReturn();
        assertThat(JsonPath.<String>read(json(moved), "$.replacement.eventId")).isEqualTo(daniEvent);   // joined Dani's event
        assertThat(classesUsed(coach, ana)).isZero();
        assertThat(JsonPath.<List<?>>read(agenda(coach, DAY, DAY), "$.events")).hasSize(1);   // her old event was emptied and cancelled
    }

    @Test
    void anInvalidNewPlaceLeavesTheOriginalOneUntouched() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var pilar = personalized(coach, "Pilar");
        String anaPlace = attendanceId(studentBooked(ana, TEN));
        studentBooked(pilar, ELEVEN);

        studentCancels(ana, anaPlace, ELEVEN).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MODALITY_MISMATCH"));
        studentCancels(ana, anaPlace, at("2026-11-09", "10:00")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("OUTSIDE_CYCLE"));
        studentCancels(ana, anaPlace, at(DAY, "10:30")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NOT_AVAILABLE"));
        studentCancels(ana, anaPlace, TEN).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_BOOKED"));   // same event

        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("SCHEDULED");
        assertThat(JsonPath.<List<?>>read(agenda(coach, DAY, DAY), "$.events")).hasSize(2);   // her event was not cancelled
    }

    @Test
    void theTwoHourWindowIsPerPlaceAndNeverAffectsTheOtherAttendees() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        String anaPlace = attendanceId(studentBooked(ana, TEN));
        studentBooked(beto, TEN);

        goTo(DAY, "08:30");   // 1 h 30 before
        studentCancels(ana, anaPlace, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANCELLATION_WINDOW_CLOSED"));
        assertThat(JsonPath.<Integer>read(agenda(coach, DAY, DAY), "$.events[0].occupied")).isEqualTo(2);

        goTo(DAY, "08:00");   // exactly 2 h before: allowed
        studentCancels(ana, anaPlace, null).andExpect(status().isOk());
        assertThat(JsonPath.<Integer>read(agenda(coach, DAY, DAY), "$.events[0].occupied")).isEqualTo(1);
    }

    // ================================================================= changing the class duration with events already booked

    @Test
    void changingTheDurationNeverMovesAnExistingEventNorLetsAnythingOverlapIt() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        studentBooked(ana, TEN);                                  // a 60-minute event 10:00-11:00

        putSettings(coach, 2, 30, 4, 200);                        // from now on classes last 30 minutes

        assertThat(JsonPath.<String>read(agenda(coach, DAY, DAY), "$.events[0].endsAt")).isEqualTo(ELEVEN);   // unchanged
        List<String> offered = JsonPath.read(studentSlots(beto, DAY, DAY), "$[*].startsAt");
        assertThat(offered).contains(at(DAY, "09:30"), ELEVEN).doesNotContain(TEN, at(DAY, "10:30"));
        // Beto cannot join it (his 30-minute range is not the event's range) nor overlap it
        studentBooks(beto, TEN).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_TAKEN"));
        studentBooks(beto, at(DAY, "10:30")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_TAKEN"));
        studentBooks(beto, ELEVEN).andExpect(status().isCreated()).andExpect(jsonPath("$.endsAt").value(at(DAY, "11:30")));
    }

    private String eventIdAt(CoachCtx coach, String startsAt) throws Exception {
        return JsonPath.<List<String>>read(agenda(coach, startsAt.substring(0, 10), startsAt.substring(0, 10)),
                "$.events[?(@.startsAt=='" + startsAt + "')].id").get(0);
    }
}
