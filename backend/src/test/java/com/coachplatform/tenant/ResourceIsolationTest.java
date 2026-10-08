package com.coachplatform.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coachplatform.support.ApiIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Two coaches, every new resource: B can neither see, list, read, change nor use anything of A (404, not 403). */
class ResourceIsolationTest extends ApiIntegrationTest {

    @Test
    void coachBCannotSeeOrTouchAnythingOfCoachA() throws Exception {
        String a = registerCoach(uniqueEmail("coach-a"));
        String b = registerCoach(uniqueEmail("coach-b"));

        String planA = createPlan(a, "8 clases", 8, 520_000);
        String studentA = createStudent(a, "Alumno de A", uniqueEmail("alumno-a"));
        String paymentJson = json(pay(a, studentA, planA, ""));
        String cycleA = JsonPath.read(paymentJson, "$.cycleId");
        // B also owns a plan and a student, so "empty" results are not just "nothing exists"
        String planB = createPlan(b, "8 clases", 8, 520_000); // same name is fine across tenants
        String studentB = createStudent(b, "Alumno de B", uniqueEmail("alumno-b"));

        // ---- plans
        mvc.perform(withToken(get("/api/coach/plans"), b)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(planB));
        mvc.perform(withToken(put("/api/coach/plans/" + planA), b).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"hack\",\"classesIncluded\":1,\"priceCop\":1,\"modality\":\"PERSONALIZED\"}")).andExpect(status().isNotFound());
        mvc.perform(withToken(patch("/api/coach/plans/" + planA + "/active"), b).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}")).andExpect(status().isNotFound());

        // ---- students
        mvc.perform(withToken(get("/api/coach/students"), b)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(studentB));
        mvc.perform(withToken(get("/api/coach/students/" + studentA), b)).andExpect(status().isNotFound());
        mvc.perform(withToken(put("/api/coach/students/" + studentA), b).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"data\":{\"fullName\":\"x\",\"email\":\"x@test.co\"}}")).andExpect(status().isNotFound());
        mvc.perform(withToken(post("/api/coach/students/" + studentA + "/invitations"), b)).andExpect(status().isNotFound());

        // ---- payments
        mvc.perform(withToken(get("/api/coach/payments").param("studentId", studentA), b)).andExpect(status().isNotFound());
        assertThat(pay(b, studentA, planB, "").getResponse().getStatus()).isEqualTo(404);   // A's student
        assertThat(pay(b, studentB, planA, "").getResponse().getStatus()).isEqualTo(404);   // A's plan

        // ---- cycles
        mvc.perform(withToken(get("/api/coach/students/" + studentA + "/cycles"), b)).andExpect(status().isNotFound());
        mvc.perform(withToken(get("/api/coach/students/" + studentA + "/cycles/active"), b)).andExpect(status().isNotFound());
        mvc.perform(withToken(post("/api/coach/cycles/" + cycleA + "/extend"), b).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEndDate\":\"2026-12-30\",\"reason\":\"intruso\"}")).andExpect(status().isNotFound());
        mvc.perform(withToken(get("/api/coach/billing/overview"), b)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].studentId").value(studentB));

        // ---- and A still has everything, untouched
        mvc.perform(withToken(get("/api/coach/students/" + studentA), a)).andExpect(jsonPath("$.fullName").value("Alumno de A"));
        mvc.perform(withToken(get("/api/coach/students/" + studentA + "/cycles/active"), a)).andExpect(jsonPath("$.endDate").value("2026-11-06"));
        mvc.perform(withToken(get("/api/coach/payments").param("studentId", studentA), a)).andExpect(jsonPath("$.length()").value(1));
        assertThat(jdbc.queryForObject("select count(*) from cycle_extension where cycle_id = ?", Integer.class,
                java.util.UUID.fromString(cycleA))).as("the intruder's extension attempt left no trace").isZero();
    }

    @Test
    void anInvitationOfCoachACannotBeUsedToReachCoachBData() throws Exception {
        String a = registerCoach(uniqueEmail("coach-a"));
        String b = registerCoach(uniqueEmail("coach-b"));
        createStudent(b, "Alumno de B", uniqueEmail("alumno-b"));
        String invite = JsonPath.read(createStudentJson(a, "Alumno de A", uniqueEmail("alumno-a")), "$.inviteUrl");
        String token = tokenFromInviteUrl(invite);

        mvc.perform(post("/api/invitations/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.studentName").value("Alumno de A"));
        assertThat(jdbc.queryForObject("select count(*) from student", Integer.class)).isGreaterThanOrEqualTo(2);
    }
}
