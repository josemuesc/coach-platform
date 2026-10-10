package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class SchedulingIsolationTest extends SchedulingApiTest {

    private static final String DAY = "2026-10-12";
    private static final String TEN = at(DAY, "10:00");

    // ================================================================= privacy: a student never learns who else is in an event

    @Test
    void noStudentEndpointReturnsTheNameOrIdOfAnotherAttendee() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Anastasia Quintero");
        var beto = semi(coach, "Beto");
        var anaBooked = studentBooked(ana, TEN);
        String anaPlace = attendanceId(anaBooked);
        String event = eventId(anaBooked);

        // everything a student can read or do, collected from BETO's side
        List<String> responses = new ArrayList<>();
        responses.add(studentSlots(beto, DAY, DAY));                                       // slots: Ana's event shows "1 of 4"
        MvcResult betoBooked = studentBooked(beto, TEN);                                  // joins Ana's event
        responses.add(json(betoBooked));
        responses.add(studentSessions(beto));
        String betoPlace = attendanceId(betoBooked);
        responses.add(json(studentCancels(beto, betoPlace, at(DAY, "15:00")).andExpect(status().isOk()).andReturn()));   // moves away
        responses.add(studentSessions(beto));
        responses.add(studentSlots(beto, DAY, DAY));
        responses.add(json(studentBooks(beto, TEN).andReturn()));                         // joins again
        responses.add(json(studentBooks(beto, TEN).andReturn()));                         // ALREADY_BOOKED error body

        for (String body : responses) {
            assertThat(body).as("a student response").doesNotContain("Anastasia").doesNotContain("Quintero")
                    .doesNotContain(ana.id()).doesNotContain(anaPlace).doesNotContain(ana.email());
        }
        // what Beto may see of the event is only counts
        assertThat(JsonPath.<Integer>read(responses.get(1), "$.occupied")).isEqualTo(2);
        assertThat(JsonPath.<String>read(responses.get(1), "$.eventId")).isEqualTo(event);   // the event id is not an attendee
        assertThat(responses.get(1)).doesNotContain("\"studentId\":\"").doesNotContain("studentName\":\"");
        assertThat(JsonPath.<List<String>>read(responses.get(0), "$[*].studentName")).isEmpty();
    }

    @Test
    void theCoachIsTheOnlyOneWhoSeesTheAttendeesOfAnEvent() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        studentBooked(ana, TEN);
        studentBooked(beto, TEN);

        assertThat(JsonPath.<List<String>>read(agenda(coach, DAY, DAY), "$.events[0].attendees[*].studentName")).containsExactlyInAnyOrder("Ana", "Beto");
        mvc.perform(withToken(get("/api/coach/agenda").param("from", DAY).param("to", DAY), ana.token())).andExpect(status().isForbidden());
        mvc.perform(withToken(get("/api/coach/attendances/pending"), ana.token())).andExpect(status().isForbidden());
    }

    // ================================================================= one student must never reach another's places

    @Test
    void aStudentSeesAndTouchesOnlyHisOwnPlaces() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        String anaPlace = attendanceId(studentBooked(ana, TEN));        // the SAME event as Beto
        String betoPlace = attendanceId(studentBooked(beto, TEN));

        assertThat(JsonPath.<List<String>>read(studentSessions(beto), "$[*].id")).containsExactly(betoPlace);
        String withParam = json(mvc.perform(withToken(get("/api/student/sessions").param("studentId", ana.id()), beto.token())).andReturn());
        assertThat(JsonPath.<List<String>>read(withParam, "$[*].id")).containsExactly(betoPlace);

        studentCancels(beto, anaPlace, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ATTENDANCE_NOT_FOUND"));
        studentCancels(beto, anaPlace, at(DAY, "15:00")).andExpect(status().isNotFound());
        studentCancels(beto, "00000000-0000-0000-0000-000000000001", null).andExpect(status().isNotFound());   // unknown looks the same
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("SCHEDULED");
    }

    @Test
    void roleAreasAreSeparated() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");

        mvc.perform(withToken(get("/api/student/sessions"), coach.token())).andExpect(status().isForbidden());
        mvc.perform(withToken(post("/api/coach/events/00000000-0000-0000-0000-000000000001/cancel"), ana.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(withToken(put("/api/coach/settings"), ana.token()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/student/sessions")).andExpect(status().isUnauthorized());
    }

    // ================================================================= coach A vs coach B

    @Test
    void coachBCannotSeeOrTouchAnythingOfCoachA() throws Exception {
        var a = newCoach(8);
        var b = newCoach(8);
        var ana = semi(a, "Ana");
        var bruno = semi(b, "Bruno");
        var anaBooked = studentBooked(ana, TEN);
        String anaPlace = attendanceId(anaBooked);
        String anaEvent = eventId(anaBooked);
        studentBooked(bruno, TEN);                                                       // the very same time at the other coach: fine
        String blockA = JsonPath.read(json(createBlock(a, "2026-10-13", null, null, "Festivo", List.of())
                .andExpect(status().isCreated()).andReturn()), "$.block.id");

        // B's agenda contains only B's event
        String agendaB = agenda(b, DAY, DAY);
        assertThat(JsonPath.<List<String>>read(agendaB, "$.events[*].attendees[*].studentName")).containsExactly("Bruno");

        // B cannot act on A's event or place
        coachCancelsEvent(b, anaEvent, "intruso").andExpect(status().isNotFound());
        changeCapacity(b, anaEvent, 3).andExpect(status().isNotFound());
        coachCancelsAttendance(b, anaPlace, "intruso", null).andExpect(status().isNotFound());
        markEvent(b, anaEvent, anaPlace, "ATTENDED").andExpect(status().isNotFound());
        goTo(DAY, "12:00");
        mark(b, anaPlace, "ATTENDED").andExpect(status().isNotFound());
        mvc.perform(withToken(get("/api/coach/students/" + ana.id() + "/sessions"), b.token())).andExpect(status().isNotFound());
        coachBooks(b, ana, at("2026-10-15", "10:00"), false, null).andExpect(status().isNotFound());
        mvc.perform(withToken(delete("/api/coach/availability/blocks/" + blockA), b.token())).andExpect(status().isNotFound());
        mvc.perform(withToken(get("/api/coach/attendances/pending"), b.token())).andExpect(jsonPath("$.length()").value(1));   // only Bruno's

        // availability is per coach
        mvc.perform(withToken(put("/api/coach/availability"), b.token()).contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isOk());
        mvc.perform(withToken(get("/api/coach/availability"), a.token())).andExpect(jsonPath("$.length()").value(7));

        // A still has everything
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("SCHEDULED");
        assertThat(JsonPath.<List<String>>read(agenda(a, DAY, DAY), "$.events[*].attendees[*].studentName")).containsExactly("Ana");
    }
}
