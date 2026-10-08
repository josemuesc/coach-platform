package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coachplatform.billing.ExpiryJobRunner;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** How classes and cycles hold on to each other: unmarked classes, renewal on the deadline day, the overview. */
class CycleSchedulingInteractionTest extends SchedulingApiTest {

    @Autowired ApplicationContext context;

    @Test
    void aCycleWithUnmarkedStartedClassesDoesNotExpireAndBlocksRenewalUntilTheyAreResolved() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");        // cycle: Oct 6 - Nov 6
        String id = studentBooked(ana, at("2026-11-05", "10:00"));

        goTo("2026-11-09", "09:00");                          // three days past the deadline, the class never marked
        // it did NOT expire: the coach has to decide that class first
        assertThat(JsonPath.<String>read(activeCycle(coach, ana), "$.status")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<Integer>read(activeCycle(coach, ana), "$.pendingMarks")).isEqualTo(1);
        ExpiryJobRunner.runOnce(context);
        assertThat(jdbc.queryForObject("select status from cycle where student_id = ?", String.class,
                java.util.UUID.fromString(ana.id()))).isEqualTo("ACTIVE");

        // the overview flags it
        mvc.perform(withToken(get("/api/coach/billing/overview"), coach.token()))
                .andExpect(jsonPath("$[?(@.fullName=='Ana')].pendingMarks").value(1));

        // renewal is refused and the answer LISTS the pending class
        var blocked = pay(coach.token(), ana.id(), coach.planId(), "");
        assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
        assertThat(JsonPath.<String>read(json(blocked), "$.code")).isEqualTo("PENDING_SESSIONS_TO_MARK");
        assertThat(JsonPath.<List<String>>read(json(blocked), "$.details.pendingSessions[*].sessionId")).containsExactly(id);

        // the coach marks it: it takes the class, and now the cycle can expire and the student renew
        mark(coach, id, "ATTENDED").andExpect(status().isOk());
        ExpiryJobRunner.runOnce(context);
        assertThat(jdbc.queryForObject("select status from cycle where student_id = ?", String.class,
                java.util.UUID.fromString(ana.id()))).isEqualTo("EXPIRED");
        assertThat(JsonPath.<Integer>read(cycles(coach, ana), "$[0].classesUsed")).isEqualTo(1);
        assertThat(pay(coach.token(), ana.id(), coach.planId(), "").getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void renewingOnTheDeadlineDayMovesTheClassesThatHaveNotStartedToTheNewCycle() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        String id = studentBooked(ana, at("2026-11-06", "19:00"));     // deadline day, evening
        String oldCycle = JsonPath.read(activeCycle(coach, ana), "$.id");

        goTo("2026-11-06", "12:00");                                    // renew at noon, the class is still ahead
        var renewed = pay(coach.token(), ana.id(), coach.planId(), "");
        assertThat(renewed.getResponse().getStatus()).isEqualTo(201);
        String newCycle = JsonPath.read(json(renewed), "$.cycleId");

        assertThat(newCycle).isNotEqualTo(oldCycle);
        assertThat(JsonPath.<String>read(studentSessions(ana), "$[0].id")).isEqualTo(id);
        assertThat(JsonPath.<String>read(studentSessions(ana), "$[0].cycleId")).isEqualTo(newCycle);
        assertThat(JsonPath.<String>read(studentSessions(ana), "$[0].status")).isEqualTo("SCHEDULED");

        // it now counts against the NEW cycle's quota, and the old one is closed
        List<String> statuses = JsonPath.read(cycles(coach, ana), "$[*].status");
        assertThat(statuses).containsExactly("ACTIVE", "EXPIRED");
        var counts = jdbc.queryForList("select count(*) c from class_session where cycle_id = ? and status = 'SCHEDULED'",
                java.util.UUID.fromString(newCycle));
        assertThat(((Number) counts.get(0).get("C")).intValue()).isEqualTo(1);

        // marking it after it happens uses a class of the NEW cycle
        goTo("2026-11-06", "19:30");
        mark(coach, id, "ATTENDED").andExpect(status().isOk());
        assertThat(JsonPath.<Integer>read(activeCycle(coach, ana), "$.classesUsed")).isEqualTo(1);
    }

    @Test
    void ifTheTransferDoesNotFitTheNewPlanTheRenewalIsRejectedAndNothingChanges() throws Exception {
        var coach = newCoach(8);
        String smallPlan = createPlan(coach.token(), "1 clase", 1, 100_000);
        var ana = newStudentWithCycle(coach, "Ana");
        String a = studentBooked(ana, at("2026-11-06", "18:00"));
        String b = studentBooked(ana, at("2026-11-06", "19:00"));

        goTo("2026-11-06", "12:00");
        var rejected = pay(coach.token(), ana.id(), smallPlan, "");
        assertThat(rejected.getResponse().getStatus()).isEqualTo(422);
        assertThat(JsonPath.<String>read(json(rejected), "$.code")).isEqualTo("TRANSFER_EXCEEDS_PLAN");

        assertThat(JsonPath.<List<String>>read(cycles(coach, ana), "$[*].status")).containsExactly("ACTIVE");   // old cycle untouched
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].id")).containsExactlyInAnyOrder(a, b);
        // the full plan accepts both
        assertThat(pay(coach.token(), ana.id(), coach.planId(), "").getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void whenTheCoachCancelsNearTheDeadlineTheCycleCanBeReopenedToPlaceTheMakeUpClass() throws Exception {
        var coach = newCoach(8);
        var ana = newStudentWithCycle(coach, "Ana");
        String id = studentBooked(ana, at("2026-11-06", "10:00"));

        goTo("2026-11-05", "20:00");
        coachCancels(coach, id, "Entrenador enfermo", at("2026-11-12", "10:00")).andExpect(status().isUnprocessableEntity())   // past the deadline
                .andExpect(jsonPath("$.code").value("OUTSIDE_CYCLE"));
        coachCancels(coach, id, "Entrenador enfermo", null).andExpect(status().isOk());

        goTo("2026-11-08", "09:00");                                                  // the cycle expired meanwhile
        var cycleId = JsonPath.<String>read(cycles(coach, ana), "$[0].id");
        mvc.perform(withToken(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/coach/cycles/" + cycleId + "/extend"), coach.token())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"newEndDate\":\"2026-11-20\",\"reason\":\"Reponer la clase cancelada por el entrenador\"}"))
                .andExpect(status().isOk());
        studentBooks(ana, at("2026-11-12", "10:00")).andExpect(status().isCreated());   // the make-up class fits now
    }
}
