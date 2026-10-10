package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * POST /api/coach/students/{id}/attendances/cancel: several of ONE student's classes cancelled by the coach, all or nothing, with the
 * same rules and the same audit line (one CANCEL per class) as the individual cancellation; and the shape of the two 409 answers of a
 * renewal that this endpoint is the way out of.
 */
class BulkCancelTest extends SchedulingApiTest {

    private ResultActions cancelMany(CoachCtx coach, String studentId, String idsJson, String reasonJson) throws Exception {
        return mvc.perform(withToken(post("/api/coach/students/" + studentId + "/attendances/cancel"), coach.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"attendanceIds\":" + idsJson + reasonJson + "}"));
    }

    private static String ids(String... ids) {
        return "[\"" + String.join("\",\"", ids) + "\"]";
    }

    private List<String> auditActions(CoachCtx coach, String attendanceId) throws Exception {
        return JsonPath.read(json(mvc.perform(withToken(get("/api/coach/attendances/" + attendanceId + "/audit"), coach.token()))
                .andExpect(status().isOk()).andReturn()), "$[*].action");
    }

    // ================================================================= the happy path

    @Test
    void cancelsAllTheClassesWithoutDeductingAndLeavesOneCancelLinePerClass() throws Exception {
        var coach = newCoach(8);
        goTo("2026-10-06", "09:00");
        var ana = personalized(coach, "Ana");
        String a = attendanceId(studentBooked(ana, at("2026-10-08", "10:00")));
        String b = attendanceId(studentBooked(ana, at("2026-10-09", "10:00")));

        cancelMany(coach, ana.id(), ids(a, b), ",\"reason\":\"  Cambio de plan  \"").andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled.length()").value(2))
                .andExpect(jsonPath("$.cancelled[0].id").value(a)).andExpect(jsonPath("$.cancelled[1].id").value(b))
                .andExpect(jsonPath("$.cancelled[0].status").value("CANCELLED_BY_COACH")).andExpect(jsonPath("$.cancelled[1].status").value("CANCELLED_BY_COACH"))
                .andExpect(jsonPath("$.cancelled[0].cancelReason").value("Cambio de plan"));

        assertThat(classesUsed(coach, ana)).isZero();
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsOnly("CANCELLED_BY_COACH");
        for (String id : List.of(a, b)) {
            assertThat(auditActions(coach, id)).containsExactly("BOOK", "CANCEL");
        }
        String audit = json(mvc.perform(withToken(get("/api/coach/attendances/" + a + "/audit"), coach.token())).andReturn());
        assertThat(JsonPath.<String>read(audit, "$[1].method")).isEqualTo("COACH");
        assertThat(JsonPath.<String>read(audit, "$[1].reason")).isEqualTo("Cambio de plan");
        // the events nobody else was in are gone from the agenda, so their slots are free again
        assertThat(JsonPath.<List<Object>>read(agenda(coach, "2026-10-06", "2026-10-31"), "$.events[?(@.status=='SCHEDULED')]")).isEmpty();
    }

    @Test
    void aSharedEventStaysForTheOthersWhenOneStudentsPlaceIsCancelled() throws Exception {
        var coach = newCoach(8);
        goTo("2026-10-06", "09:00");
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        String a = attendanceId(studentBooked(ana, at("2026-10-08", "10:00")));
        studentBooked(beto, at("2026-10-08", "10:00"));

        cancelMany(coach, ana.id(), ids(a), ",\"reason\":\"Lluvia\"").andExpect(status().isOk());
        var events = agenda(coach, "2026-10-08", "2026-10-08");
        assertThat(JsonPath.<List<String>>read(events, "$.events[*].status")).containsExactly("SCHEDULED");
        assertThat(JsonPath.<List<String>>read(events, "$.events[0].attendees[*].studentName")).containsExactly("Beto");
    }

    // ================================================================= all or nothing

    @Test
    void aMarkedClassInTheListRefusesTheWholeOperationAndNothingIsCancelled() throws Exception {
        var coach = newCoach(8);
        goTo("2026-10-06", "09:00");
        var ana = personalized(coach, "Ana");
        String early = attendanceId(studentBooked(ana, at("2026-10-08", "10:00")));
        String late = attendanceId(studentBooked(ana, at("2026-10-09", "10:00")));
        goTo("2026-10-08", "11:00");
        mark(coach, early, "ATTENDED").andExpect(status().isOk());

        cancelMany(coach, ana.id(), ids(late, early), ",\"reason\":\"x\"").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE"));

        var sessions = studentSessions(ana);
        assertThat(JsonPath.<List<String>>read(sessions, "$[?(@.id=='" + late + "')].status")).as("the valid one was NOT cancelled").containsExactly("SCHEDULED");
        assertThat(JsonPath.<List<String>>read(sessions, "$[?(@.id=='" + early + "')].status")).containsExactly("ATTENDED");
        assertThat(auditActions(coach, late)).as("no audit line for the rolled-back one").containsExactly("BOOK");
        assertThat(classesUsed(coach, ana)).isEqualTo(1);
    }

    @Test
    void anotherStudentsClassOrAnUnknownOneFailsEverythingAndRevealsNothing() throws Exception {
        var coach = newCoach(8);
        goTo("2026-10-06", "09:00");
        var ana = personalized(coach, "Ana");
        var beto = personalized(coach, "Beto");
        String mine = attendanceId(studentBooked(ana, at("2026-10-08", "10:00")));
        String his = attendanceId(studentBooked(beto, at("2026-10-08", "12:00")));

        cancelMany(coach, ana.id(), ids(mine, his), ",\"reason\":\"x\"").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ATTENDANCE_NOT_FOUND"));
        cancelMany(coach, ana.id(), ids(mine, "00000000-0000-0000-0000-000000000000"), ",\"reason\":\"x\"").andExpect(status().isNotFound());
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("SCHEDULED");
        assertThat(JsonPath.<List<String>>read(studentSessions(beto), "$[*].status")).containsExactly("SCHEDULED");
        assertThat(auditActions(coach, mine)).containsExactly("BOOK");
        assertThat(auditActions(coach, his)).containsExactly("BOOK");
    }

    @Test
    void anotherCoachCannotCancelAndTheBodyIsStrict() throws Exception {
        var coach = newCoach(8);
        goTo("2026-10-06", "09:00");
        var ana = personalized(coach, "Ana");
        String a = attendanceId(studentBooked(ana, at("2026-10-08", "10:00")));
        var other = newCoach(8);
        cancelMany(other, ana.id(), ids(a), ",\"reason\":\"x\"").andExpect(status().isNotFound());

        cancelMany(coach, ana.id(), ids(a), "").andExpect(status().isBadRequest());                         // reason is mandatory
        cancelMany(coach, ana.id(), ids(a), ",\"reason\":\"   \"").andExpect(status().isBadRequest());
        cancelMany(coach, ana.id(), "[]", ",\"reason\":\"x\"").andExpect(status().isBadRequest());        // at least one
        cancelMany(coach, ana.id(), "[\"no-es-uuid\"]", ",\"reason\":\"x\"").andExpect(status().isBadRequest());
        String tooMany = "[" + String.join(",", java.util.Collections.nCopies(51, "\"" + a + "\"")) + "]";
        cancelMany(coach, ana.id(), tooMany, ",\"reason\":\"x\"").andExpect(status().isBadRequest());
        mvc.perform(withToken(post("/api/coach/students/" + ana.id() + "/attendances/cancel"), ana.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"attendanceIds\":" + ids(a) + ",\"reason\":\"x\"}")).andExpect(status().isForbidden());   // a student cannot
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].status")).containsExactly("SCHEDULED");
    }

    @Test
    void repeatedIdsCountOnce() throws Exception {
        var coach = newCoach(8);
        goTo("2026-10-06", "09:00");
        var ana = personalized(coach, "Ana");
        String a = attendanceId(studentBooked(ana, at("2026-10-08", "10:00")));
        cancelMany(coach, ana.id(), ids(a, a), ",\"reason\":\"x\"").andExpect(status().isOk()).andExpect(jsonPath("$.cancelled.length()").value(1));
        assertThat(auditActions(coach, a)).containsExactly("BOOK", "CANCEL");
    }

    // ================================================================= the way out of a modality conflict, and the shape of the 409s

    @Test
    void cancellingTheConflictingClassesInOneStepLetsTheRenewalThrough() throws Exception {
        var coach = newCoach(8);
        goTo("2026-10-06", "09:00");
        var ana = personalized(coach, "Ana");
        String place = attendanceId(studentBooked(ana, at("2026-11-06", "19:00")));
        String other = attendanceId(studentBooked(ana, at("2026-11-06", "18:00")));

        goTo("2026-11-06", "12:00");
        var refused = pay(coach.token(), ana.id(), coach.semiPlan(), "");
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        List<String> conflicting = JsonPath.read(json(refused), "$.details.conflictingAttendances[*].attendanceId");
        assertThat(conflicting).containsExactlyInAnyOrder(place, other);

        cancelMany(coach, ana.id(), ids(conflicting.toArray(new String[0])), ",\"reason\":\"Pasa al plan grupal\"").andExpect(status().isOk());
        assertThat(pay(coach.token(), ana.id(), coach.semiPlan(), "").getResponse().getStatus()).isEqualTo(201);
        assertThat(classesUsed(coach, ana)).isZero();
    }

    @Test
    void theShapeOfTheModalityConflictAnswerIsFixed() throws Exception {
        var coach = newCoach(8);
        goTo("2026-10-06", "09:00");
        var ana = personalized(coach, "Ana");
        studentBooked(ana, at("2026-11-06", "19:00"));
        goTo("2026-11-06", "12:00");
        var refused = pay(coach.token(), ana.id(), coach.semiPlan(), "");
        String body = json(refused);

        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("MODALITY_CONFLICT_ON_RENEWAL");
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.details")).containsOnlyKeys("newPlanModality", "conflictingAttendances", "explanation", "options");
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.details.conflictingAttendances[0]"))
                .containsOnlyKeys("attendanceId", "sessionId", "studentId", "startsAt", "endsAt", "eventModality");
        assertThat(JsonPath.<List<Map<String, Object>>>read(body, "$.details.options")).allSatisfy(o -> assertThat(o).containsOnlyKeys("code", "description"));
        assertThat(JsonPath.<List<String>>read(body, "$.details.options[*].code")).containsExactly("CANCEL_WITHOUT_PENALTY", "OVERRIDE");
        // the third way out (OVERRIDE) needs a reason: without it the same request is refused with its own code
        var noReason = mvc.perform(withToken(post("/api/coach/students/" + ana.id() + "/payments"), coach.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"planId\":\"" + coach.semiPlan() + "\",\"amountCop\":320000,\"method\":\"CASH\",\"overrideModality\":true}")).andReturn();
        assertThat(noReason.getResponse().getStatus()).isEqualTo(422);
        assertThat(JsonPath.<String>read(json(noReason), "$.code")).isEqualTo("OVERRIDE_REASON_REQUIRED");
    }

    @Test
    void theShapeOfThePendingSessionsAnswerIsFixedAndCarriesTheEventModality() throws Exception {
        var coach = newCoach(8);
        goTo("2026-10-06", "09:00");
        var ana = personalized(coach, "Ana");
        String place = attendanceId(studentBooked(ana, at("2026-11-05", "10:00")));
        goTo("2026-11-09", "09:00");
        var blocked = pay(coach.token(), ana.id(), coach.personalizedPlan(), "");
        String body = json(blocked);

        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("PENDING_SESSIONS_TO_MARK");
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.details")).containsOnlyKeys("pendingSessions");
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.details.pendingSessions[0]"))
                .containsOnlyKeys("attendanceId", "sessionId", "studentId", "startsAt", "endsAt", "eventModality");
        assertThat(JsonPath.<String>read(body, "$.details.pendingSessions[0].attendanceId")).isEqualTo(place);
        assertThat(JsonPath.<String>read(body, "$.details.pendingSessions[0].eventModality")).isEqualTo("PERSONALIZED");
    }
}
