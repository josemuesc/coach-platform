package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coachplatform.billing.ExpiryJobRunner;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;

/** How attendances and cycles hold on to each other: unmarked places, renewal on the deadline day, modality conflicts. */
class CycleSchedulingInteractionTest extends SchedulingApiTest {

    @Autowired ApplicationContext context;

    private String storedStatus(StudentCtx s) {
        return jdbc.queryForObject("select status from cycle where student_id = ?", String.class, UUID.fromString(s.id()));
    }

    private String payWith(CoachCtx coach, StudentCtx s, String planId, String extraJson) throws Exception {
        return json(pay(coach.token(), s.id(), planId, extraJson));
    }

    // ================================================================= unmarked places hold the cycle open

    @Test
    void aCycleWithUnmarkedStartedPlacesDoesNotExpireAndBlocksRenewalUntilTheyAreResolved() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");               // cycle: Oct 6 - Nov 6
        String place = attendanceId(studentBooked(ana, at("2026-11-05", "10:00")));

        goTo("2026-11-09", "09:00");                          // three days past the deadline, the class never marked
        assertThat(JsonPath.<String>read(activeCycle(coach, ana), "$.status")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<Integer>read(activeCycle(coach, ana), "$.pendingMarks")).isEqualTo(1);
        ExpiryJobRunner.runOnce(context);
        assertThat(storedStatus(ana)).isEqualTo("ACTIVE");

        mvc.perform(withToken(get("/api/coach/billing/overview"), coach.token()))
                .andExpect(jsonPath("$[?(@.fullName=='Ana')].pendingMarks").value(1));

        var blocked = pay(coach.token(), ana.id(), coach.personalizedPlan(), "");
        assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
        assertThat(JsonPath.<String>read(json(blocked), "$.code")).isEqualTo("PENDING_SESSIONS_TO_MARK");
        assertThat(JsonPath.<List<String>>read(json(blocked), "$.details.pendingSessions[*].attendanceId")).containsExactly(place);

        mark(coach, place, "ATTENDED").andExpect(status().isOk());
        ExpiryJobRunner.runOnce(context);
        assertThat(storedStatus(ana)).isEqualTo("EXPIRED");
        assertThat(pay(coach.token(), ana.id(), coach.personalizedPlan(), "").getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void twoStudentsInTheSameStartedEventCountAsTwoPendingPlaces() throws Exception {
        var coach = newCoach(8);
        var ana = semi(coach, "Ana");
        var beto = semi(coach, "Beto");
        studentBooked(ana, at("2026-11-05", "10:00"));
        studentBooked(beto, at("2026-11-05", "10:00"));

        goTo("2026-11-09", "09:00");
        assertThat(JsonPath.<Integer>read(activeCycle(coach, ana), "$.pendingMarks")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(activeCycle(coach, beto), "$.pendingMarks")).isEqualTo(1);
        mvc.perform(withToken(get("/api/coach/attendances/pending"), coach.token())).andExpect(jsonPath("$.length()").value(2));
    }

    // ================================================================= renewal on the deadline day

    @Test
    void renewingOnTheDeadlineDayMovesThePlacesThatHaveNotStartedToTheNewCycle() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String place = attendanceId(studentBooked(ana, at("2026-11-06", "19:00")));
        String oldCycle = JsonPath.read(activeCycle(coach, ana), "$.id");

        goTo("2026-11-06", "12:00");
        var renewed = pay(coach.token(), ana.id(), coach.personalizedPlan(), "");
        assertThat(renewed.getResponse().getStatus()).isEqualTo(201);
        String newCycle = JsonPath.read(json(renewed), "$.cycleId");

        assertThat(newCycle).isNotEqualTo(oldCycle);
        assertThat(JsonPath.<String>read(studentSessions(ana), "$[0].id")).isEqualTo(place);
        assertThat(JsonPath.<String>read(studentSessions(ana), "$[0].cycleId")).isEqualTo(newCycle);
        assertThat(JsonPath.<Boolean>read(studentSessions(ana), "$[0].override")).as("same modality: no override flag").isFalse();
        assertThat(JsonPath.<List<String>>read(cycles(coach, ana), "$[*].status")).containsExactly("ACTIVE", "EXPIRED");

        goTo("2026-11-06", "19:30");
        mark(coach, place, "ATTENDED").andExpect(status().isOk());
        assertThat(classesUsed(coach, ana)).isEqualTo(1);                                // a class of the NEW cycle
    }

    @Test
    void ifTheTransferDoesNotFitTheNewPlanTheRenewalIsRejectedAndNothingChanges() throws Exception {
        var coach = newCoach(8);
        String smallPlan = createPlan(coach.token(), "1 clase", 1, 100_000, "PERSONALIZED");
        var ana = personalized(coach, "Ana");
        String a = attendanceId(studentBooked(ana, at("2026-11-06", "18:00")));
        String b = attendanceId(studentBooked(ana, at("2026-11-06", "19:00")));

        goTo("2026-11-06", "12:00");
        var rejected = pay(coach.token(), ana.id(), smallPlan, "");
        assertThat(rejected.getResponse().getStatus()).isEqualTo(422);
        assertThat(JsonPath.<String>read(json(rejected), "$.code")).isEqualTo("TRANSFER_EXCEEDS_PLAN");
        assertThat(JsonPath.<List<String>>read(cycles(coach, ana), "$[*].status")).containsExactly("ACTIVE");
        assertThat(JsonPath.<List<String>>read(studentSessions(ana), "$[*].id")).containsExactlyInAnyOrder(a, b);
        assertThat(pay(coach.token(), ana.id(), coach.personalizedPlan(), "").getResponse().getStatus()).isEqualTo(201);
    }

    // ================================================================= renewal with a different modality

    @Test
    void renewingWithAPlanOfAnotherModalityWhileHavingBookedClassesIsRefusedWithAClearExplanation() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String place = attendanceId(studentBooked(ana, at("2026-11-06", "19:00")));       // a PERSONALIZED event, deadline day, evening

        goTo("2026-11-06", "12:00");
        var refused = pay(coach.token(), ana.id(), coach.semiPlan(), "");
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        String body = json(refused);
        assertThat(JsonPath.<String>read(body, "$.code")).isEqualTo("MODALITY_CONFLICT_ON_RENEWAL");
        assertThat(JsonPath.<String>read(body, "$.details.newPlanModality")).isEqualTo("SEMI_PERSONALIZED");
        assertThat(JsonPath.<List<String>>read(body, "$.details.conflictingAttendances[*].attendanceId")).containsExactly(place);
        assertThat(JsonPath.<List<String>>read(body, "$.details.conflictingAttendances[*].eventModality")).containsExactly("PERSONALIZED");
        assertThat(JsonPath.<String>read(body, "$.details.explanation")).contains("1 class(es)").contains("another modality");
        assertThat(JsonPath.<List<String>>read(body, "$.details.options[*].code")).containsExactly("CANCEL_WITHOUT_PENALTY", "OVERRIDE");

        // nothing changed: same cycle, same class
        assertThat(JsonPath.<List<String>>read(cycles(coach, ana), "$[*].status")).containsExactly("ACTIVE");
        assertThat(JsonPath.<String>read(studentSessions(ana), "$[0].status")).isEqualTo("SCHEDULED");
    }

    @Test
    void optionOneTheCoachCancelsThoseClassesWithoutPenaltyAndThenRenews() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String place = attendanceId(studentBooked(ana, at("2026-11-06", "19:00")));

        goTo("2026-11-06", "12:00");
        assertThat(pay(coach.token(), ana.id(), coach.semiPlan(), "").getResponse().getStatus()).isEqualTo(409);

        coachCancelsAttendance(coach, place, "Cambio de plan a grupal", null).andExpect(status().isOk());
        var renewed = pay(coach.token(), ana.id(), coach.semiPlan(), "");
        assertThat(renewed.getResponse().getStatus()).isEqualTo(201);
        assertThat(classesUsed(coach, ana)).as("the cancelled class cost nothing").isZero();
        assertThat(JsonPath.<String>read(activeCycle(coach, ana), "$.modality")).isEqualTo("SEMI_PERSONALIZED");
    }

    @Test
    void optionTwoTheCoachForcesTheTransferWithAReasonAndItIsAuditedInTheAgenda() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String place = attendanceId(studentBooked(ana, at("2026-11-06", "19:00")));
        goTo("2026-11-06", "12:00");

        // the override needs a reason
        var noReason = pay(coach.token(), ana.id(), coach.semiPlan(), ",\"overrideModality\":true");
        assertThat(noReason.getResponse().getStatus()).isEqualTo(422);
        assertThat(JsonPath.<String>read(json(noReason), "$.code")).isEqualTo("OVERRIDE_REASON_REQUIRED");

        var forced = pay(coach.token(), ana.id(), coach.semiPlan(), ",\"overrideModality\":true,\"overrideReason\":\"Termina esta clase y sigue en grupal\"");
        assertThat(forced.getResponse().getStatus()).isEqualTo(201);
        String newCycle = JsonPath.read(json(forced), "$.cycleId");

        assertThat(JsonPath.<String>read(studentSessions(ana), "$[0].id")).isEqualTo(place);
        assertThat(JsonPath.<String>read(studentSessions(ana), "$[0].cycleId")).isEqualTo(newCycle);
        assertThat(JsonPath.<Boolean>read(studentSessions(ana), "$[0].override")).isTrue();
        String agenda = agenda(coach, "2026-11-06", "2026-11-06");
        assertThat(JsonPath.<List<Boolean>>read(agenda, "$.events[0].attendees[*].override")).containsExactly(true);
        assertThat(JsonPath.<List<String>>read(agenda, "$.events[0].attendees[*].overrideReason").get(0))
                .contains("Termina esta clase y sigue en grupal");
    }

    @Test
    void aPaymentWithoutBookedClassesIsNotAffectedByTheModalityOfThePlan() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        goTo("2026-11-06", "12:00");
        assertThat(pay(coach.token(), ana.id(), coach.semiPlan(), "").getResponse().getStatus()).isEqualTo(201);
        assertThat(JsonPath.<String>read(activeCycle(coach, ana), "$.modality")).isEqualTo("SEMI_PERSONALIZED");
    }

    // ================================================================= reopening after the coach cancels near the deadline

    @Test
    void whenTheCoachCancelsNearTheDeadlineTheCycleCanBeReopenedToPlaceTheMakeUpClass() throws Exception {
        var coach = newCoach(8);
        var ana = personalized(coach, "Ana");
        String place = attendanceId(studentBooked(ana, at("2026-11-06", "10:00")));

        goTo("2026-11-05", "20:00");
        coachCancelsAttendance(coach, place, "Entrenador enfermo", at("2026-11-12", "10:00")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OUTSIDE_CYCLE"));
        coachCancelsAttendance(coach, place, "Entrenador enfermo", null).andExpect(status().isOk());

        goTo("2026-11-08", "09:00");                                                    // the cycle expired meanwhile
        String cycleId = JsonPath.read(cycles(coach, ana), "$[0].id");
        mvc.perform(withToken(post("/api/coach/cycles/" + cycleId + "/extend"), coach.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEndDate\":\"2026-11-20\",\"reason\":\"Reponer la clase cancelada por el entrenador\"}"))
                .andExpect(status().isOk());
        studentBooks(ana, at("2026-11-12", "10:00")).andExpect(status().isCreated());
    }
}
