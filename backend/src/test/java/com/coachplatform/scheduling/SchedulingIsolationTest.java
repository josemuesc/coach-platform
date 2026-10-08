package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class SchedulingIsolationTest extends SchedulingApiTest {

    // ---------------------------------------------------------------- one student must never reach another's classes

    @Test
    void aStudentSeesAndTouchesOnlyHisOwnClasses() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        var beto = newStudentWithCycle(coach, "Beto");
        String anaClass = studentBooked(ana, at("2026-10-12", "10:00"));
        String betoClass = studentBooked(beto, at("2026-10-12", "11:00"));

        // lists are built from the token: Beto sees only his own, even if he tries to ask for Ana's id
        assertThat(JsonPath.<List<String>>read(studentSessions(beto), "$[*].id")).containsExactly(betoClass);
        String withParam = json(mvc.perform(withToken(get("/api/student/sessions").param("studentId", ana.id()), beto.token())).andReturn());
        assertThat(JsonPath.<List<String>>read(withParam, "$[*].id")).containsExactly(betoClass);

        // cancelling or moving somebody else's class is a plain 404 and changes nothing
        studentCancels(beto, anaClass, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
        studentCancels(beto, anaClass, at("2026-10-14", "10:00")).andExpect(status().isNotFound());
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("SCHEDULED");

        // an unknown id looks exactly the same as somebody else's
        studentCancels(beto, "00000000-0000-0000-0000-000000000001", null).andExpect(status().isNotFound());
    }

    @Test
    void roleAreasAreSeparated() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");

        mvc.perform(withToken(get("/api/student/sessions"), coach.token())).andExpect(status().isForbidden());   // a coach is no student
        mvc.perform(withToken(get("/api/coach/agenda").param("from", "2026-10-12").param("to", "2026-10-12"), ana.token()))
                .andExpect(status().isForbidden());
        mvc.perform(withToken(get("/api/coach/sessions/pending"), ana.token())).andExpect(status().isForbidden());
        mvc.perform(get("/api/student/sessions")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- coach A vs coach B

    @Test
    void coachBCannotSeeOrTouchAnythingOfCoachAInTheAgenda() throws Exception {
        var a = newCoach(8);
        var b = newCoach(8);
        var ana = newStudentWithCycle(a, "Ana");
        newStudentWithCycle(b, "Bruno");
        String classA = studentBooked(ana, at("2026-10-12", "10:00"));
        var blockJson = json(mvc.perform(withToken(post("/api/coach/availability/blocks"), a.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"startsAt\":\"" + at("2026-10-13", "00:00") + "\",\"endsAt\":\"" + at("2026-10-14", "00:00") + "\"}"))
                .andExpect(status().isCreated()).andReturn());
        String blockA = JsonPath.read(blockJson, "$.block.id");

        // B's agenda is empty of A's class, and B's free slots are not reduced by A's booking
        mvc.perform(withToken(get("/api/coach/agenda").param("from", "2026-10-12").param("to", "2026-10-12"), b.token()))
                .andExpect(jsonPath("$.sessions.length()").value(0)).andExpect(jsonPath("$.freeSlots.length()").value(14));
        mvc.perform(withToken(get("/api/coach/sessions/pending"), b.token())).andExpect(jsonPath("$.length()").value(0));

        // B cannot cancel, mark or list A's class, nor book A's student, nor remove A's block
        coachCancels(b, classA, "intruso", null).andExpect(status().isNotFound());
        goTo("2026-10-12", "12:00");
        mark(b, classA, "ATTENDED").andExpect(status().isNotFound());
        mvc.perform(withToken(get("/api/coach/students/" + ana.id() + "/sessions"), b.token())).andExpect(status().isNotFound());
        coachBooks(b, ana.id(), at("2026-10-15", "10:00")).andExpect(status().isNotFound());
        mvc.perform(withToken(delete("/api/coach/availability/blocks/" + blockA), b.token())).andExpect(status().isNotFound());
        mvc.perform(withToken(get("/api/coach/availability/blocks").param("from", at("2026-10-01", "00:00")).param("to", at("2026-10-31", "00:00")), b.token()))
                .andExpect(jsonPath("$.length()").value(0));

        // availability is per coach: B replacing it does not touch A's
        mvc.perform(withToken(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/coach/availability"), b.token())
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isOk());
        mvc.perform(withToken(get("/api/coach/availability"), a.token())).andExpect(jsonPath("$.length()").value(7));

        // A still has everything, untouched
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("SCHEDULED");
        mvc.perform(withToken(get("/api/coach/availability/blocks").param("from", at("2026-10-01", "00:00")).param("to", at("2026-10-31", "00:00")), a.token()))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void theSameTimeAtTwoDifferentCoachesIsNotAConflict() throws Exception {
        var a = newCoach(8);
        var b = newCoach(8);
        var ana = newStudentWithCycle(a, "Ana");
        var bruno = newStudentWithCycle(b, "Bruno");

        studentBooks(ana, at("2026-10-12", "10:00")).andExpect(status().isCreated());
        studentBooks(bruno, at("2026-10-12", "10:00")).andExpect(status().isCreated());
    }
}
