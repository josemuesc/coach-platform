package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AvailabilityAndSettingsTest extends SchedulingApiTest {

    private String putAvailability(CoachCtx coach, String json, int expectedStatus) throws Exception {
        return json(mvc.perform(withToken(put("/api/coach/availability"), coach.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().is(expectedStatus)).andReturn());
    }

    private void putSettings(CoachCtx coach, int cancelWindow, int duration, int expectedStatus) throws Exception {
        mvc.perform(withToken(put("/api/coach/settings"), coach.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cancelWindowHours\":" + cancelWindow + ",\"classDurationMinutes\":" + duration
                        + ",\"expiringSoonDays\":5,\"expiringSoonClasses\":1,\"maxExtensionDays\":60}"))
                .andExpect(status().is(expectedStatus));
    }

    // ---------------------------------------------------------------- weekly availability

    @Test
    void theWeeklyAvailabilityIsReplacedAsAWhole() throws Exception {
        var coach = newCoach(8);
        putAvailability(coach, "[{\"dayOfWeek\":1,\"start\":\"06:00\",\"end\":\"09:00\"},{\"dayOfWeek\":3,\"start\":\"17:00\",\"end\":\"20:00\"}]", 200);

        mvc.perform(withToken(get("/api/coach/availability"), coach.token()))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].dayOfWeek").value(1)).andExpect(jsonPath("$[0].start").value("06:00"))
                .andExpect(jsonPath("$[1].dayOfWeek").value(3)).andExpect(jsonPath("$[1].end").value("20:00"));
    }

    @Test
    void invalidWindowsAreRejected() throws Exception {
        var coach = newCoach(8);
        putAvailability(coach, "[{\"dayOfWeek\":1,\"start\":\"09:00\",\"end\":\"06:00\"}]", 422);                 // end before start
        putAvailability(coach, "[{\"dayOfWeek\":1,\"start\":\"06:00\",\"end\":\"09:00\"},{\"dayOfWeek\":1,\"start\":\"08:00\",\"end\":\"10:00\"}]", 422);   // overlap
        putAvailability(coach, "[{\"dayOfWeek\":1,\"start\":\"6am\",\"end\":\"09:00\"}]", 422);                    // bad time
        putAvailability(coach, "[{\"dayOfWeek\":8,\"start\":\"06:00\",\"end\":\"09:00\"}]", 400);                  // bad day
        mvc.perform(withToken(get("/api/coach/availability"), coach.token())).andExpect(jsonPath("$.length()").value(7));   // untouched
    }

    @Test
    void changingTheAvailabilityNeverCancelsClassesAlreadyBooked() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        String id = studentBooked(ana, at("2026-10-12", "10:00"));   // a Monday

        putAvailability(coach, "[]", 200);   // no availability at all from now on

        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("SCHEDULED");
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].id")).containsExactly(id);
        studentBooks(ana, at("2026-10-13", "10:00")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOT_AVAILABLE"));
        // and the class that is already booked can still be cancelled normally
        studentCancels(ana, id, null).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- blocks

    @Test
    void aBlockListsTheClassesInsideItButCancelsNothing() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        var beto = newStudentWithCycle(coach, "Beto");
        String a = studentBooked(ana, at("2026-10-12", "10:00"));
        studentBooked(beto, at("2026-10-12", "15:00"));          // outside the block

        var created = mvc.perform(withToken(post("/api/coach/availability/blocks"), coach.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startsAt\":\"" + at("2026-10-12", "09:00") + "\",\"endsAt\":\"" + at("2026-10-12", "12:00") + "\",\"reason\":\"Festivo\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.block.reason").value("Festivo"))
                .andExpect(jsonPath("$.affectedSessions.length()").value(1))
                .andExpect(jsonPath("$.affectedSessions[0].id").value(a))
                .andExpect(jsonPath("$.affectedSessions[0].studentName").value("Ana")).andReturn();

        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("SCHEDULED");   // not cancelled
        String blockId = JsonPath.read(json(created), "$.block.id");

        // nobody can book inside the block, everything else still works
        studentBooks(beto, at("2026-10-12", "11:00")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BLOCKED"));
        studentBooks(beto, at("2026-10-12", "13:00")).andExpect(status().isCreated());

        mvc.perform(withToken(delete("/api/coach/availability/blocks/" + blockId), coach.token())).andExpect(status().isNoContent());
        studentBooks(beto, at("2026-10-12", "11:00")).andExpect(status().isCreated());
    }

    @Test
    void blockedHoursDisappearFromTheFreeSlots() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        mvc.perform(withToken(post("/api/coach/availability/blocks"), coach.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"startsAt\":\"" + at("2026-10-12", "00:00") + "\",\"endsAt\":\"" + at("2026-10-13", "00:00") + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.affectedSessions.length()").value(0));

        mvc.perform(withToken(get("/api/student/slots").param("from", "2026-10-12").param("to", "2026-10-13"), ana.token()))
                .andExpect(jsonPath("$.length()").value(14))     // only Oct 13 is left
                .andExpect(jsonPath("$[0].localDate").value("2026-10-13"));
    }

    @Test
    void aBlockMustEndAfterItStarts() throws Exception {
        var coach = newCoach(8);
        mvc.perform(withToken(post("/api/coach/availability/blocks"), coach.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"startsAt\":\"" + at("2026-10-12", "12:00") + "\",\"endsAt\":\"" + at("2026-10-12", "09:00") + "\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_BLOCK"));
    }

    // ---------------------------------------------------------------- settings

    @Test
    void theSettingsHaveDefaultsAndValidatedRanges() throws Exception {
        var coach = newCoach(8);
        mvc.perform(withToken(get("/api/coach/settings"), coach.token()))
                .andExpect(jsonPath("$.cancelWindowHours").value(2)).andExpect(jsonPath("$.classDurationMinutes").value(60))
                .andExpect(jsonPath("$.maxExtensionDays").value(60));

        putSettings(coach, 24, 45, 200);
        putSettings(coach, 0, 15, 200);
        putSettings(coach, 48, 180, 200);
        putSettings(coach, 49, 60, 400);      // window: 0-48 h
        putSettings(coach, -1, 60, 400);
        putSettings(coach, 2, 14, 400);       // duration: 15-180 min
        putSettings(coach, 2, 181, 400);
        mvc.perform(withToken(get("/api/coach/settings"), coach.token()))
                .andExpect(jsonPath("$.cancelWindowHours").value(48)).andExpect(jsonPath("$.classDurationMinutes").value(180));
    }

    @Test
    void aChangedCancellationWindowIsUsedByTheServer() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        putSettings(coach, 24, 60, 200);
        String id = studentBooked(ana, at("2026-10-20", "10:00"));

        goTo("2026-10-19", "11:00");   // 23 h before
        studentCancels(ana, id, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANCELLATION_WINDOW_CLOSED"));
        goTo("2026-10-19", "10:00");   // exactly 24 h before
        studentCancels(ana, id, null).andExpect(status().isOk());
    }

    @Test
    void changingTheDurationOnlyAffectsClassesBookedAfterwards() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        var beto = newStudentWithCycle(coach, "Beto");
        String old = studentBooked(ana, at("2026-10-12", "10:00"));   // booked as a 60-minute class
        assertThat(JsonPath.<String>read(studentSessions(ana), "$[0].endsAt")).isEqualTo(at("2026-10-12", "11:00"));

        putSettings(coach, 2, 30, 200);   // from now on classes last 30 minutes

        // the existing class keeps its original end
        assertThat(JsonPath.<String>read(studentSessions(ana), "$[0].endsAt")).isEqualTo(at("2026-10-12", "11:00"));
        // new bookings use the 30-minute grid, and the old class's whole hour is still taken
        List<String> starts = JsonPath.read(json(mvc.perform(withToken(get("/api/student/slots").param("from", "2026-10-12").param("to", "2026-10-12"), beto.token())).andReturn()), "$[*].startsAt");
        assertThat(starts).contains(at("2026-10-12", "09:30"), at("2026-10-12", "11:00"), at("2026-10-12", "11:30"))
                .doesNotContain(at("2026-10-12", "10:00"), at("2026-10-12", "10:30"));
        studentBooks(beto, at("2026-10-12", "10:30")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_TAKEN"));
        studentBooks(beto, at("2026-10-12", "11:00")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.endsAt").value(at("2026-10-12", "11:30")));
        assertThat(old).isNotBlank();
    }
}
