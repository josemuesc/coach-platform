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
 * Helpers for HTTP-level scheduling tests. The clock starts at noon on Tuesday 2026-10-06 (Bogota); a cycle opened that day
 * ends on 2026-11-06. Every test creates its own coach, so tests never share a calendar. Each coach has availability every
 * day 06:00-20:00 (14 one-hour slots) and two plans: one PERSONALIZED and one SEMI_PERSONALIZED.
 */
public abstract class SchedulingApiTest extends ApiIntegrationTest {

    protected static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    protected record CoachCtx(String email, String token, String personalizedPlan, String semiPlan) {
    }

    protected record StudentCtx(String id, String name, String email, String token) {
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
        return new CoachCtx(email, token,
                createPlan(token, "personalizado " + planClasses, planClasses, 520_000, "PERSONALIZED"),
                createPlan(token, "semi " + planClasses, planClasses, 320_000, "SEMI_PERSONALIZED"));
    }

    /** A student with a login (invitation accepted), not yet paid. */
    protected StudentCtx newStudent(CoachCtx coach, String name) throws Exception {
        String email = uniqueEmail("alumno");
        String json = createStudentJson(coach.token(), name, email);
        String inviteToken = tokenFromInviteUrl(JsonPath.read(json, "$.inviteUrl"));
        mvc.perform(post("/api/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                .content(com.coachplatform.support.ConsentFixtures.acceptJson(inviteToken, PASSWORD))).andExpect(status().isOk());
        return new StudentCtx(JsonPath.read(json, "$.student.id"), name, email, login(email, PASSWORD));
    }

    protected StudentCtx personalized(CoachCtx coach, String name) throws Exception {
        return withCycle(coach, newStudent(coach, name), coach.personalizedPlan());
    }

    protected StudentCtx semi(CoachCtx coach, String name) throws Exception {
        return withCycle(coach, newStudent(coach, name), coach.semiPlan());
    }

    protected StudentCtx withCycle(CoachCtx coach, StudentCtx student, String planId) throws Exception {
        MvcResult paid = pay(coach.token(), student.id(), planId, "");
        if (paid.getResponse().getStatus() != 201) {
            throw new AssertionError("payment failed: " + paid.getResponse().getContentAsString());
        }
        return student;
    }

    // ---- student actions ----
    protected ResultActions studentBooks(StudentCtx s, String startsAt) throws Exception {
        return mvc.perform(withToken(post("/api/student/sessions"), s.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"startsAt\":\"" + startsAt + "\"}"));
    }

    protected MvcResult studentBooked(StudentCtx s, String startsAt) throws Exception {
        return studentBooks(s, startsAt).andExpect(status().isCreated()).andReturn();
    }

    protected String attendanceId(MvcResult booked) throws Exception {
        return JsonPath.read(json(booked), "$.id");
    }

    protected String eventId(MvcResult booked) throws Exception {
        return JsonPath.read(json(booked), "$.eventId");
    }

    protected ResultActions studentCancels(StudentCtx s, String attendanceId, String newStartsAt) throws Exception {
        String body = newStartsAt == null ? "{}" : "{\"newStartsAt\":\"" + newStartsAt + "\"}";
        return mvc.perform(withToken(post("/api/student/sessions/" + attendanceId + "/cancel"), s.token())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected String studentSessions(StudentCtx s) throws Exception {
        return json(mvc.perform(withToken(get("/api/student/sessions"), s.token())).andExpect(status().isOk()).andReturn());
    }

    protected String studentSlots(StudentCtx s, String from, String to) throws Exception {
        return json(mvc.perform(withToken(get("/api/student/slots").param("from", from).param("to", to), s.token()))
                .andExpect(status().isOk()).andReturn());
    }

    // ---- coach actions ----
    protected ResultActions coachBooks(CoachCtx c, StudentCtx s, String startsAt, boolean override, String reason) throws Exception {
        String body = "{\"startsAt\":\"" + startsAt + "\",\"override\":" + override
                + (reason == null ? "" : ",\"overrideReason\":\"" + reason + "\"") + "}";
        return mvc.perform(withToken(post("/api/coach/students/" + s.id() + "/sessions"), c.token())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions coachCancelsAttendance(CoachCtx c, String attendanceId, String reason, String newStartsAt) throws Exception {
        String body = "{\"reason\":\"" + reason + "\"" + (newStartsAt == null ? "" : ",\"newStartsAt\":\"" + newStartsAt + "\"") + "}";
        return mvc.perform(withToken(post("/api/coach/attendances/" + attendanceId + "/cancel"), c.token())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions coachCancelsEvent(CoachCtx c, String eventId, String reason) throws Exception {
        return mvc.perform(withToken(post("/api/coach/events/" + eventId + "/cancel"), c.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}"));
    }

    protected ResultActions mark(CoachCtx c, String attendanceId, String result) throws Exception {
        return mvc.perform(withToken(post("/api/coach/attendances/" + attendanceId + "/mark"), c.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"" + result + "\"}"));
    }

    protected ResultActions markEvent(CoachCtx c, String eventId, String... pairs) throws Exception {
        StringBuilder marks = new StringBuilder("[");
        for (int i = 0; i < pairs.length; i += 2) {
            marks.append(i > 0 ? "," : "").append("{\"attendanceId\":\"").append(pairs[i]).append("\",\"status\":\"").append(pairs[i + 1]).append("\"}");
        }
        return mvc.perform(withToken(post("/api/coach/events/" + eventId + "/mark"), c.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"marks\":" + marks.append("]") + "}"));
    }

    protected ResultActions changeCapacity(CoachCtx c, String eventId, int capacity) throws Exception {
        return mvc.perform(withToken(put("/api/coach/events/" + eventId + "/capacity"), c.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":" + capacity + "}"));
    }

    protected String agenda(CoachCtx c, String from, String to) throws Exception {
        return json(mvc.perform(withToken(get("/api/coach/agenda").param("from", from).param("to", to), c.token()))
                .andExpect(status().isOk()).andReturn());
    }

    protected void putSettings(CoachCtx c, int cancelWindow, int duration, int groupCapacity, int expectedStatus) throws Exception {
        mvc.perform(withToken(put("/api/coach/settings"), c.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cancelWindowHours\":" + cancelWindow + ",\"classDurationMinutes\":" + duration
                        + ",\"expiringSoonDays\":5,\"expiringSoonClasses\":1,\"maxExtensionDays\":60,\"defaultGroupCapacity\":" + groupCapacity
                        + ",\"confirmationWindowHours\":72,\"qrOpenMinutesBefore\":15,\"qrCloseHoursAfterEnd\":2,\"gymConsentConfirmed\":false}"))
                .andExpect(status().is(expectedStatus));
    }

    protected String activeCycle(CoachCtx c, StudentCtx s) throws Exception {
        return json(mvc.perform(withToken(get("/api/coach/students/" + s.id() + "/cycles/active"), c.token()))
                .andExpect(status().isOk()).andReturn());
    }

    protected String cycles(CoachCtx c, StudentCtx s) throws Exception {
        return json(mvc.perform(withToken(get("/api/coach/students/" + s.id() + "/cycles"), c.token()))
                .andExpect(status().isOk()).andReturn());
    }

    protected int classesUsed(CoachCtx c, StudentCtx s) throws Exception {
        return JsonPath.<Integer>read(activeCycle(c, s), "$.classesUsed");
    }
}
