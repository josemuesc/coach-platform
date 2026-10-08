package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** What the coach does with a whole event: cancel it, mark it, see it. And attendance, which is per place. */
class EventManagementTest extends SchedulingApiTest {

    private static final String DAY = "2026-10-12";
    private static final String TEN = at(DAY, "10:00");

    // ================================================================= cancelling the whole event

    @Test
    void cancellingTheWholeEventCancelsEveryPlaceChargesNobodyAndListsTheAffectedStudents() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        var carla = semi(coach, "Carla");
        String event = eventId(studentBooked(ana, TEN));
        studentBooked(beto, TEN);
        studentBooked(carla, TEN);

        coachCancelsEvent(coach, event, "   ").andExpect(status().isBadRequest());                      // the reason is mandatory

        var result = coachCancelsEvent(coach, event, "Entrenador enfermo").andExpect(status().isOk())
                .andExpect(jsonPath("$.event.status").value("CANCELLED"))
                .andExpect(jsonPath("$.affectedStudents.length()").value(3)).andReturn();
        assertThat(JsonPath.<List<String>>read(json(result), "$.affectedStudents[*].studentName")).containsExactlyInAnyOrder("Ana", "Beto", "Carla");

        for (var student : List.of(ana, beto, carla)) {
            assertThat(JsonPath.<List<String>>read(studentSessions(student), "$[*].status")).containsExactly("CANCELLED_BY_COACH");
            assertThat(JsonPath.<List<String>>read(studentSessions(student), "$[*].cancelReason")).containsExactly("Entrenador enfermo");
            assertThat(classesUsed(coach, student)).as(student.name() + " is not charged").isZero();
        }
        assertThat(JsonPath.<List<?>>read(agenda(coach, DAY, DAY), "$.events")).isEmpty();
        coachCancelsEvent(coach, event, "otra vez").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void anEventWithAMarkedClassIsNeverCancelledAsAWholeOnlyItsPendingPlacesAre() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        var anaPlace = attendanceId(studentBooked(ana, TEN));
        studentBooked(beto, TEN);
        String event = JsonPath.<List<String>>read(agenda(coach, DAY, DAY), "$.events[*].id").get(0);

        goTo(DAY, "10:30");
        mark(coach, anaPlace, "ATTENDED").andExpect(status().isOk());
        var result = coachCancelsEvent(coach, event, "Se acabó el tiempo").andExpect(status().isOk())
                .andExpect(jsonPath("$.event.status").value("SCHEDULED"))                // it did take place for Ana
                .andExpect(jsonPath("$.affectedStudents.length()").value(1)).andReturn();
        assertThat(JsonPath.<String>read(json(result), "$.affectedStudents[0].studentName")).isEqualTo("Beto");

        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("ATTENDED");
        assertThat(classesUsed(coach, ana)).isEqualTo(1);
        assertThat(classesUsed(coach, beto)).isZero();
    }

    @Test
    void theCoachCanCancelJustOneStudentsPlaceAndTheEventContinues() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        String anaPlace = attendanceId(studentBooked(ana, TEN));
        studentBooked(beto, TEN);

        coachCancelsAttendance(coach, anaPlace, "   ", null).andExpect(status().isBadRequest());
        coachCancelsAttendance(coach, anaPlace, "Se lesionó", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled.status").value("CANCELLED_BY_COACH"));

        String agenda = agenda(coach, DAY, DAY);
        assertThat(JsonPath.<Integer>read(agenda, "$.events[0].occupied")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(studentSessions(beto), "$[*].status")).containsExactly("SCHEDULED");
        assertThat(classesUsed(coach, ana)).isZero();
    }

    @Test
    void aLateStudentCancellationIsForgivenByTheCoachCancellingThatPlaceAlone() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        String anaPlace = attendanceId(studentBooked(ana, TEN));
        studentBooked(beto, TEN);

        goTo(DAY, "09:00");
        studentCancels(ana, anaPlace, null).andExpect(status().isConflict());                          // inside the window: refused
        coachCancelsAttendance(coach, anaPlace, "Perdonada: llovía muy fuerte", null).andExpect(status().isOk());

        assertThat(classesUsed(coach, ana)).isZero();
        assertThat(JsonPath.<Integer>read(agenda(coach, DAY, DAY), "$.events[0].occupied")).isEqualTo(1);   // Beto's class is unaffected
    }

    // ================================================================= attendance is per place

    @Test
    void markingConsumesAClassOfEachStudentsOwnCycleAndNothingElse() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        String anaPlace = attendanceId(studentBooked(ana, TEN));
        String betoPlace = attendanceId(studentBooked(beto, TEN));

        mark(coach, anaPlace, "ATTENDED").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLASS_NOT_STARTED"));
        goTo(DAY, "10:00");
        mark(coach, anaPlace, "ATTENDED").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ATTENDED"));

        assertThat(classesUsed(coach, ana)).isEqualTo(1);
        assertThat(classesUsed(coach, beto)).isZero();                                 // still unmarked
        mark(coach, betoPlace, "NO_SHOW").andExpect(status().isOk());
        assertThat(classesUsed(coach, beto)).isEqualTo(1);

        mark(coach, anaPlace, "NO_SHOW").andExpect(status().isOk());                  // switching keeps the count
        assertThat(classesUsed(coach, ana)).isEqualTo(1);
        mark(coach, anaPlace, "NO_SHOW").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_MARKED"));
    }

    @Test
    void pendingMarksAreCountedPerPlaceNotPerEvent() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        String anaPlace = attendanceId(studentBooked(ana, TEN));
        studentBooked(beto, TEN);

        goTo(DAY, "12:00");
        mvc.perform(withToken(get("/api/coach/attendances/pending"), coach.token()))
                .andExpect(jsonPath("$.length()").value(2));                           // one event, two pending places
        mvc.perform(withToken(get("/api/coach/billing/overview"), coach.token()))
                .andExpect(jsonPath("$[?(@.fullName=='Ana')].pendingMarks").value(1))
                .andExpect(jsonPath("$[?(@.fullName=='Beto')].pendingMarks").value(1));

        mark(coach, anaPlace, "ATTENDED").andExpect(status().isOk());
        mvc.perform(withToken(get("/api/coach/attendances/pending"), coach.token()))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].studentName").value("Beto"));
    }

    // ================================================================= marking several students of one event at once

    @Test
    void theCoachMarksSeveralStudentsOfAnEventInOneCall() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        var carla = semi(coach, "Carla");
        String event = eventId(studentBooked(ana, TEN));
        String a = "n/a";
        studentBooked(beto, TEN);
        studentBooked(carla, TEN);
        goTo(DAY, "11:00");
        var places = JsonPath.<List<String>>read(agenda(coach, DAY, DAY), "$.events[0].attendees[*].attendanceId");
        var names = JsonPath.<List<String>>read(agenda(coach, DAY, DAY), "$.events[0].attendees[*].studentName");

        var args = new String[places.size() * 2];
        for (int i = 0; i < places.size(); i++) {
            args[i * 2] = places.get(i);
            args[i * 2 + 1] = names.get(i).equals("Carla") ? "NO_SHOW" : "ATTENDED";
        }
        markEvent(coach, event, args).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));

        assertThat(classesUsed(coach, ana)).isEqualTo(1);
        assertThat(classesUsed(coach, beto)).isEqualTo(1);
        assertThat(classesUsed(coach, carla)).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(studentSessions(carla), "$[*].status")).containsExactly("NO_SHOW");
        assertThat(a).isNotBlank();
    }

    @Test
    void theBatchMarkIsAllOrNothing() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        String anaPlace = attendanceId(studentBooked(ana, TEN));
        String betoPlace = attendanceId(studentBooked(beto, TEN));
        String event = JsonPath.<List<String>>read(agenda(coach, DAY, DAY), "$.events[*].id").get(0);
        goTo(DAY, "11:00");
        mark(coach, betoPlace, "ATTENDED").andExpect(status().isOk());                 // Beto is already marked ATTENDED

        // Ana's mark is valid, Beto's repeats his result: the whole batch is refused and Ana stays untouched
        markEvent(coach, event, anaPlace, "ATTENDED", betoPlace, "ATTENDED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MARKED"));
        assertThat(classesUsed(coach, ana)).isZero();
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("SCHEDULED");

        // the same batch with a valid second mark does apply (switching Beto's result)
        markEvent(coach, event, anaPlace, "ATTENDED", betoPlace, "NO_SHOW").andExpect(status().isOk());
        assertThat(classesUsed(coach, ana)).isEqualTo(1);
        assertThat(classesUsed(coach, beto)).isEqualTo(1);
    }

    @Test
    void theBatchRejectsDuplicatesAttendancesOfOtherEventsAndEmptyLists() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        String anaPlace = attendanceId(studentBooked(ana, TEN));
        String otherEventPlace = attendanceId(studentBooked(beto, at(DAY, "15:00")));
        String event = JsonPath.<List<String>>read(agenda(coach, DAY, DAY), "$.events[?(@.startsAt=='" + TEN + "')].id").get(0);
        goTo(DAY, "16:00");

        markEvent(coach, event, anaPlace, "ATTENDED", anaPlace, "NO_SHOW").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ATTENDANCE"));
        markEvent(coach, event, otherEventPlace, "ATTENDED").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ATTENDANCE_NOT_IN_EVENT"));
        mvc.perform(withToken(post("/api/coach/events/" + event + "/mark"), coach.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"marks\":[]}")).andExpect(status().isBadRequest());
        assertThat(classesUsed(coach, ana)).isZero();
        assertThat(classesUsed(coach, beto)).isZero();
    }

    // ================================================================= blocks

    @Test
    void aBlockListsTheEventsInsideItWithTheirAttendeesButCancelsNothing() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        var pilar = personalized(coach, "Pilar");
        String event = eventId(studentBooked(ana, TEN));
        studentBooked(beto, TEN);
        studentBooked(pilar, at(DAY, "15:00"));                                         // outside the block

        var created = mvc.perform(withToken(post("/api/coach/availability/blocks"), coach.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startsAt\":\"" + at(DAY, "09:00") + "\",\"endsAt\":\"" + at(DAY, "12:00") + "\",\"reason\":\"Festivo\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.affectedEvents.length()").value(1))
                .andExpect(jsonPath("$.affectedEvents[0].id").value(event))
                .andReturn();
        assertThat(JsonPath.<List<String>>read(json(created), "$.affectedEvents[0].attendees[*].studentName")).containsExactlyInAnyOrder("Ana", "Beto");
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("SCHEDULED");   // nothing was cancelled
    }
}
