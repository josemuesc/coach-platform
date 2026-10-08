package com.coachplatform.scheduling;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coachplatform.support.ApiIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Helpers for HTTP-level scheduling tests. The clock starts at noon on Tuesday 2026-10-06 (Bogota); a coach's cycle
 * opened that day ends on 2026-11-06. Every test creates its own coach, so tests never share a calendar.
 */
public abstract class SchedulingApiTest extends ApiIntegrationTest {

    protected static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    /** A coach with availability every day 06:00-20:00 (14 one-hour slots). */
    protected record CoachCtx(String email, String token, String planId) {
    }

    protected record StudentCtx(String id, String email, String token) {
    }

    protected static String at(String date, String time) {
        return LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(BOGOTA).toInstant().toString();
    }

    protected void goTo(String date, String time) {
        clock.set(Instant.parse(at(date, time)));
    }

    protected void advance(Duration d) {
        clock.advance(d);
    }

    protected CoachCtx newCoach(int planClasses) throws Exception {
        String email = uniqueEmail("coach");
        String token = registerCoach(email);
        StringBuilder windows = new StringBuilder("[");
        for (int day = 1; day <= 7; day++) {
            windows.append(day > 1 ? "," : "").append("{\"dayOfWeek\":").append(day).append(",\"start\":\"06:00\",\"end\":\"20:00\"}");
        }
        mvc.perform(withToken(put("/api/coach/availability"), token).contentType(MediaType.APPLICATION_JSON)
                .content(windows.append("]").toString())).andExpect(status().isOk());
        return new CoachCtx(email, token, createPlan(token, planClasses + " clases", planClasses, 520_000));
    }

    /** A student with a login (invitation accepted). Without paying: no cycle yet. */
    protected StudentCtx newStudent(CoachCtx coach, String name) throws Exception {
        String email = uniqueEmail("alumno");
        String json = createStudentJson(coach.token(), name, email);
        String inviteToken = tokenFromInviteUrl(JsonPath.read(json, "$.inviteUrl"));
        mvc.perform(post("/api/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + inviteToken + "\",\"password\":\"" + PASSWORD + "\"}")).andExpect(status().isOk());
        return new StudentCtx(JsonPath.read(json, "$.student.id"), email, login(email, PASSWORD));
    }

    protected StudentCtx newStudentWithCycle(CoachCtx coach, String name) throws Exception {
        StudentCtx student = newStudent(coach, name);
        MvcResult paid = pay(coach.token(), student.id(), coach.planId(), "");
        if (paid.getResponse().getStatus() != 201) {
            throw new AssertionError("payment failed: " + paid.getResponse().getContentAsString());
        }
        return student;
    }

    protected ResultActions studentBooks(StudentCtx s, String startsAt) throws Exception {
        return mvc.perform(withToken(post("/api/student/sessions"), s.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"startsAt\":\"" + startsAt + "\"}"));
    }

    protected String studentBooked(StudentCtx s, String startsAt) throws Exception {
        MvcResult r = studentBooks(s, startsAt).andExpect(status().isCreated()).andReturn();
        return JsonPath.read(json(r), "$.id");
    }

    protected ResultActions coachBooks(CoachCtx c, String studentId, String startsAt) throws Exception {
        return mvc.perform(withToken(post("/api/coach/students/" + studentId + "/sessions"), c.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"startsAt\":\"" + startsAt + "\"}"));
    }

    protected ResultActions studentCancels(StudentCtx s, String sessionId, String newStartsAt) throws Exception {
        String body = newStartsAt == null ? "{}" : "{\"newStartsAt\":\"" + newStartsAt + "\"}";
        return mvc.perform(withToken(post("/api/student/sessions/" + sessionId + "/cancel"), s.token())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions coachCancels(CoachCtx c, String sessionId, String reason, String newStartsAt) throws Exception {
        String body = "{\"reason\":\"" + reason + "\"" + (newStartsAt == null ? "" : ",\"newStartsAt\":\"" + newStartsAt + "\"") + "}";
        return mvc.perform(withToken(post("/api/coach/sessions/" + sessionId + "/cancel"), c.token())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions mark(CoachCtx c, String sessionId, String result) throws Exception {
        return mvc.perform(withToken(post("/api/coach/sessions/" + sessionId + "/attendance"), c.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"" + result + "\"}"));
    }

    protected String studentSessions(StudentCtx s) throws Exception {
        return json(mvc.perform(withToken(get("/api/student/sessions"), s.token())).andExpect(status().isOk()).andReturn());
    }

    protected String activeCycle(CoachCtx c, StudentCtx s) throws Exception {
        return json(mvc.perform(withToken(get("/api/coach/students/" + s.id() + "/cycles/active"), c.token()))
                .andExpect(status().isOk()).andReturn());
    }

    protected String cycles(CoachCtx c, StudentCtx s) throws Exception {
        return json(mvc.perform(withToken(get("/api/coach/students/" + s.id() + "/cycles"), c.token()))
                .andExpect(status().isOk()).andReturn());
    }
}
