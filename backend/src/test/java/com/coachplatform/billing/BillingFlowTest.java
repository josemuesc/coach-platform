package com.coachplatform.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coachplatform.support.ApiIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class BillingFlowTest extends ApiIntegrationTest {

    @Autowired CycleExpiryJob expiryJob;

    private String email;
    private String coachToken;
    private String planId;
    private String studentId;

    @BeforeEach
    void setUp() throws Exception {
        email = uniqueEmail("coach");
        coachToken = registerCoach(email);
        planId = createPlan(coachToken, "8 clases", 8, 520_000);
        studentId = createStudent(coachToken, "Ana", uniqueEmail("alumno"));
    }

    private void atBogota(String date) {
        clock.set(Instant.parse(date + "T17:00:00Z")); // noon in Bogota
        try {
            coachToken = login(email, PASSWORD); // tokens expire with the (moved) clock
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String activeCycleJson() throws Exception {
        return json(mvc.perform(withToken(get("/api/coach/students/" + studentId + "/cycles/active"), coachToken))
                .andExpect(status().isOk()).andReturn());
    }

    @Test
    void aPaymentOpensTheCycleAndASecondOneIsBlockedWhileItIsActive() throws Exception {
        var paid = pay(coachToken, studentId, planId, "");
        assertThat(paid.getResponse().getStatus()).isEqualTo(201);
        String body = json(paid);
        assertThat(JsonPath.<String>read(body, "$.startDate")).isEqualTo("2026-10-06");
        assertThat(JsonPath.<String>read(body, "$.endDate")).isEqualTo("2026-11-06");

        String cycle = activeCycleJson();
        assertThat(JsonPath.<Integer>read(cycle, "$.classesRemaining")).isEqualTo(8);
        assertThat(JsonPath.<String>read(cycle, "$.status")).isEqualTo("ACTIVE");

        var second = pay(coachToken, studentId, planId, "");
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(JsonPath.<String>read(json(second), "$.code")).isEqualTo("ACTIVE_CYCLE_EXISTS");

        mvc.perform(withToken(get("/api/coach/payments").param("studentId", studentId), coachToken))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amountCop").value(520000))
                .andExpect(jsonPath("$[0].method").value("NEQUI"));
        assertThat(jdbc.queryForObject("select count(*) from cycle where status = 'ACTIVE'", Integer.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void amountDefaultsToThePlanPriceAndCanBeOverridden() throws Exception {
        var paid = pay(coachToken, studentId, planId, ",\"amountCop\":450000");
        assertThat(paid.getResponse().getStatus()).isEqualTo(201);
        mvc.perform(withToken(get("/api/coach/payments").param("studentId", studentId), coachToken))
                .andExpect(jsonPath("$[0].amountCop").value(450000));
    }

    @Test
    void renewalOnTheDeadlineDayClosesTheOldCycleAsExpiredAndOpensANewOne() throws Exception {
        pay(coachToken, studentId, planId, "");

        atBogota("2026-11-05");
        assertThat(pay(coachToken, studentId, planId, "").getResponse().getStatus()).isEqualTo(409); // before the deadline day

        atBogota("2026-11-06");
        var renewed = pay(coachToken, studentId, planId, "");
        assertThat(renewed.getResponse().getStatus()).isEqualTo(201);
        assertThat(JsonPath.<String>read(json(renewed), "$.startDate")).isEqualTo("2026-11-06");

        var cycles = mvc.perform(withToken(get("/api/coach/students/" + studentId + "/cycles"), coachToken))
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> list = JsonPath.read(json(cycles), "$");
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("status")).isEqualTo("ACTIVE");
        assertThat(list.get(1).get("status")).isEqualTo("EXPIRED");
        assertThat(list.get(1).get("classesLost")).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from cycle where student_id = ? and status = 'ACTIVE'",
                Integer.class, java.util.UUID.fromString(studentId))).isEqualTo(1);
    }

    @Test
    void aLatePaymentStartsTheNewCycleOnThePaymentDay() throws Exception {
        pay(coachToken, studentId, planId, "");

        atBogota("2026-11-12");
        var late = pay(coachToken, studentId, planId, "");

        assertThat(late.getResponse().getStatus()).isEqualTo(201);
        assertThat(JsonPath.<String>read(json(late), "$.startDate")).isEqualTo("2026-11-12");
        assertThat(JsonPath.<String>read(json(late), "$.endDate")).isEqualTo("2026-12-12");
    }

    @Test
    void paymentDateRules() throws Exception {
        var future = pay(coachToken, studentId, planId, ",\"paidOn\":\"2026-10-07\"");
        assertThat(future.getResponse().getStatus()).isEqualTo(422);
        assertThat(JsonPath.<String>read(json(future), "$.code")).isEqualTo("INVALID_PAYMENT_DATE");

        assertThat(pay(coachToken, studentId, planId, ",\"paidOn\":\"2026-10-02\"").getResponse().getStatus()).isEqualTo(422);

        var threeDaysBack = pay(coachToken, studentId, planId, ",\"paidOn\":\"2026-10-03\"");
        assertThat(threeDaysBack.getResponse().getStatus()).isEqualTo(201);
        assertThat(JsonPath.<String>read(json(threeDaysBack), "$.startDate")).isEqualTo("2026-10-03");
        assertThat(JsonPath.<String>read(json(threeDaysBack), "$.endDate")).isEqualTo("2026-11-03");
    }

    @Test
    void anInactivePlanCannotBeSold() throws Exception {
        mvc.perform(withToken(patch("/api/coach/plans/" + planId + "/active"), coachToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk());

        var paid = pay(coachToken, studentId, planId, "");
        assertThat(paid.getResponse().getStatus()).isEqualTo(404);
        assertThat(JsonPath.<String>read(json(paid), "$.code")).isEqualTo("PLAN_NOT_FOUND");
    }

    @Test
    void extendingRecordsWhoWhenAndWhy() throws Exception {
        pay(coachToken, studentId, planId, "");
        String cycleId = JsonPath.read(activeCycleJson(), "$.id");
        var coachUserId = userId(coachToken);

        mvc.perform(withToken(post("/api/coach/cycles/" + cycleId + "/extend"), coachToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEndDate\":\"2026-11-13\",\"reason\":\"Entrenador enfermo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endDate").value("2026-11-13"))
                .andExpect(jsonPath("$.originalEndDate").value("2026-11-06"));

        var rows = jdbc.queryForList("select previous_end_date, new_end_date, reason, extended_by, extended_at from cycle_extension where cycle_id = ?",
                java.util.UUID.fromString(cycleId));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("REASON")).isEqualTo("Entrenador enfermo");
        assertThat(rows.get(0).get("EXTENDED_BY").toString()).isEqualTo(coachUserId.toString());
        assertThat(rows.get(0).get("EXTENDED_AT")).isNotNull();
        assertThat(rows.get(0).get("PREVIOUS_END_DATE").toString()).isEqualTo("2026-11-06");

        // moving the deadline backwards (or not at all) is rejected
        mvc.perform(withToken(post("/api/coach/cycles/" + cycleId + "/extend"), coachToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEndDate\":\"2026-11-10\",\"reason\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_EXTENSION"));
    }

    @Test
    void extensionIsCappedAt60DaysPastTheOriginalDeadlineByDefault() throws Exception {
        pay(coachToken, studentId, planId, "");
        String cycleId = JsonPath.read(activeCycleJson(), "$.id");

        mvc.perform(withToken(post("/api/coach/cycles/" + cycleId + "/extend"), coachToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEndDate\":\"2027-01-06\",\"reason\":\"demasiado\"}"))   // 61 days after Nov 6
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EXTENSION_LIMIT_EXCEEDED"));

        mvc.perform(withToken(post("/api/coach/cycles/" + cycleId + "/extend"), coachToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEndDate\":\"2027-01-05\",\"reason\":\"justo en el tope\"}"))  // exactly 60 days
                .andExpect(status().isOk()).andExpect(jsonPath("$.endDate").value("2027-01-05"));
    }

    @Test
    void theExtensionCapAndTheExpiringSoonThresholdsAreConfigurablePerCoach() throws Exception {
        jdbc.update("update coach_settings set max_extension_days = 10, expiring_soon_days = 20 "
                + "where coach_id = (select coach_id from app_user where email = ?)", email);
        pay(coachToken, studentId, planId, "");
        String cycleId = JsonPath.read(activeCycleJson(), "$.id");

        mvc.perform(withToken(post("/api/coach/cycles/" + cycleId + "/extend"), coachToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEndDate\":\"2026-11-17\",\"reason\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EXTENSION_LIMIT_EXCEEDED"));
        mvc.perform(withToken(post("/api/coach/cycles/" + cycleId + "/extend"), coachToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEndDate\":\"2026-11-16\",\"reason\":\"x\"}"))
                .andExpect(status().isOk());

        // 20 days before the (extended) deadline of Nov 16 is already "expiring soon" for this coach (default would be 5)
        atBogota("2026-10-30");
        mvc.perform(withToken(get("/api/coach/billing/overview"), coachToken))
                .andExpect(jsonPath("$[?(@.fullName=='Ana')].status").value("EXPIRING_SOON"));
    }

    @Test
    void anExpiredCycleCannotBeExtended() throws Exception {
        pay(coachToken, studentId, planId, "");
        String cycleId = JsonPath.read(activeCycleJson(), "$.id");

        atBogota("2026-11-08");
        mvc.perform(withToken(post("/api/coach/cycles/" + cycleId + "/extend"), coachToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEndDate\":\"2026-11-20\",\"reason\":\"tarde\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CYCLE_NOT_ACTIVE"));
    }

    @Test
    void readsShowTheEffectiveStateAndTheDailyJobMakesTheStoredStatusCatchUp() throws Exception {
        pay(coachToken, studentId, planId, "");

        atBogota("2026-11-20");
        // Effective state is already EXPIRED although nothing has closed it yet.
        mvc.perform(withToken(get("/api/coach/students/" + studentId + "/cycles/active"), coachToken))
                .andExpect(status().isNotFound());
        mvc.perform(withToken(get("/api/coach/students/" + studentId + "/cycles"), coachToken))
                .andExpect(jsonPath("$[0].status").value("EXPIRED"));
        assertThat(storedStatuses()).containsExactly("ACTIVE");

        expiryJob.run();

        assertThat(storedStatuses()).containsExactly("EXPIRED");
        expiryJob.run(); // idempotent
        assertThat(storedStatuses()).containsExactly("EXPIRED");
    }

    private List<String> storedStatuses() {
        return jdbc.queryForList("select status from cycle where student_id = ?", String.class, java.util.UUID.fromString(studentId));
    }

    @Test
    void overviewShowsActiveExpiringSoonAndNoCycle() throws Exception {
        String second = createStudent(coachToken, "Beto", uniqueEmail("alumno"));
        pay(coachToken, studentId, planId, "");

        mvc.perform(withToken(get("/api/coach/billing/overview"), coachToken))
                .andExpect(jsonPath("$[?(@.fullName=='Ana')].status").value("ACTIVE"))
                .andExpect(jsonPath("$[?(@.fullName=='Beto')].status").value("NO_CYCLE"));

        atBogota("2026-11-03"); // 3 days left
        mvc.perform(withToken(get("/api/coach/billing/overview"), coachToken))
                .andExpect(jsonPath("$[?(@.fullName=='Ana')].status").value("EXPIRING_SOON"));
        assertThat(second).isNotBlank();

        atBogota("2026-11-20");
        mvc.perform(withToken(get("/api/coach/billing/overview"), coachToken))
                .andExpect(jsonPath("$[?(@.fullName=='Ana')].status").value("NO_CYCLE"));
    }

    @Test
    void anAdvancedClockDoesNotLeakIntoOtherTests() {
        clock.advance(Duration.ofDays(400)); // resetClock() restores it before the next test
    }
}
