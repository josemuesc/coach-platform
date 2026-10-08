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

    private void putAvailability(CoachCtx coach, String json, int expectedStatus) throws Exception {
        mvc.perform(withToken(put("/api/coach/availability"), coach.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().is(expectedStatus));
    }

    @Test
    void theWeeklyAvailabilityIsReplacedAsAWholeAndInvalidWindowsAreRejected() throws Exception {
        var coach = newCoach(8);
        putAvailability(coach, "[{\"dayOfWeek\":1,\"start\":\"06:00\",\"end\":\"09:00\"},{\"dayOfWeek\":3,\"start\":\"17:00\",\"end\":\"20:00\"}]", 200);
        mvc.perform(withToken(get("/api/coach/availability"), coach.token()))
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].dayOfWeek").value(1)).andExpect(jsonPath("$[1].end").value("20:00"));

        putAvailability(coach, "[{\"dayOfWeek\":1,\"start\":\"09:00\",\"end\":\"06:00\"}]", 422);
        putAvailability(coach, "[{\"dayOfWeek\":1,\"start\":\"06:00\",\"end\":\"09:00\"},{\"dayOfWeek\":1,\"start\":\"08:00\",\"end\":\"10:00\"}]", 422);
        putAvailability(coach, "[{\"dayOfWeek\":1,\"start\":\"6am\",\"end\":\"09:00\"}]", 422);
        putAvailability(coach, "[{\"dayOfWeek\":8,\"start\":\"06:00\",\"end\":\"09:00\"}]", 400);
        mvc.perform(withToken(get("/api/coach/availability"), coach.token())).andExpect(jsonPath("$.length()").value(2));   // untouched
    }

    @Test
    void changingTheAvailabilityNeverCancelsPlacesAlreadyBooked() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        String anaPlace = attendanceId(studentBooked(ana, at("2026-10-12", "10:00")));
        studentBooked(beto, at("2026-10-12", "10:00"));

        putAvailability(coach, "[]", 200);

        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("SCHEDULED");
        assertThat(JsonPath.<List<String>>read(studentSessions(beto), "$[*].status")).containsExactly("SCHEDULED");
        studentBooks(ana, at("2026-10-13", "10:00")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NOT_AVAILABLE"));
        studentCancels(ana, anaPlace, null).andExpect(status().isOk());   // and it can still be cancelled
    }

    @Test
    void blockedHoursCannotBeBookedAndDisappearFromTheSlots() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String blockId = JsonPath.read(json(mvc.perform(withToken(post("/api/coach/availability/blocks"), coach.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"startsAt\":\"" + at("2026-10-12", "00:00") + "\",\"endsAt\":\"" + at("2026-10-13", "00:00") + "\",\"reason\":\"Festivo\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.affectedEvents.length()").value(0)).andReturn()), "$.block.id");

        List<String> days = JsonPath.read(studentSlots(ana, "2026-10-12", "2026-10-13"), "$[*].localDate");
        assertThat(days).hasSize(14).containsOnly("2026-10-13");
        studentBooks(ana, at("2026-10-12", "10:00")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BLOCKED"));

        mvc.perform(withToken(delete("/api/coach/availability/blocks/" + blockId), coach.token())).andExpect(status().isNoContent());
        studentBooks(ana, at("2026-10-12", "10:00")).andExpect(status().isCreated());
        mvc.perform(withToken(post("/api/coach/availability/blocks"), coach.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"startsAt\":\"" + at("2026-10-12", "12:00") + "\",\"endsAt\":\"" + at("2026-10-12", "09:00") + "\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_BLOCK"));
    }

    @Test
    void theSettingsHaveDefaultsAndValidatedRanges() throws Exception {
        var coach = newCoach(8);
        mvc.perform(withToken(get("/api/coach/settings"), coach.token()))
                .andExpect(jsonPath("$.cancelWindowHours").value(2)).andExpect(jsonPath("$.classDurationMinutes").value(60))
                .andExpect(jsonPath("$.defaultGroupCapacity").value(4)).andExpect(jsonPath("$.maxExtensionDays").value(60));

        putSettings(coach, 24, 45, 6, 200);
        putSettings(coach, 0, 15, 2, 200);
        putSettings(coach, 48, 180, 10, 200);
        putSettings(coach, 49, 60, 4, 400);       // window: 0-48 h
        putSettings(coach, -1, 60, 4, 400);
        putSettings(coach, 2, 14, 4, 400);        // duration: 15-180 min
        putSettings(coach, 2, 181, 4, 400);
        putSettings(coach, 2, 60, 1, 400);        // group capacity: 2-10
        putSettings(coach, 2, 60, 11, 400);
        mvc.perform(withToken(get("/api/coach/settings"), coach.token()))
                .andExpect(jsonPath("$.cancelWindowHours").value(48)).andExpect(jsonPath("$.classDurationMinutes").value(180))
                .andExpect(jsonPath("$.defaultGroupCapacity").value(10));
    }

    @Test
    void aChangedCancellationWindowIsUsedByTheServer() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        putSettings(coach, 24, 60, 4, 200);
        String place = attendanceId(studentBooked(ana, at("2026-10-20", "10:00")));

        goTo("2026-10-19", "11:00");   // 23 h before
        studentCancels(ana, place, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANCELLATION_WINDOW_CLOSED"));
        goTo("2026-10-19", "10:00");   // exactly 24 h before
        studentCancels(ana, place, null).andExpect(status().isOk());
    }
}
