package com.coachplatform.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** HTTP-level tests on H2 with a movable clock (starts at noon, Oct 6 2026, Bogota). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
public abstract class ApiIntegrationTest {

    protected static final String PASSWORD = "Prueba-1234-x";

    @Autowired protected MockMvc mvc;
    @Autowired protected MutableClock clock;
    @Autowired protected JdbcTemplate jdbc;

    @BeforeEach
    void resetClock() {
        clock.set(Instant.parse("2026-10-06T17:00:00Z"));
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@test.co";
    }

    protected static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    protected static MockHttpServletRequestBuilder fromIp(MockHttpServletRequestBuilder request, String ip) {
        return request.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }

    protected String json(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    /** Registers a coach and returns its JWT. */
    protected String registerCoach(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/register-coach").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Coach " + email + "\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(json(r), "$.token");
    }

    protected String login(String email, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(json(r), "$.token");
    }

    protected UUID userId(String token) throws Exception {
        MvcResult r = mvc.perform(withToken(get("/api/me"), token)).andExpect(status().isOk()).andReturn();
        return UUID.fromString(JsonPath.read(json(r), "$.userId"));
    }

    protected String createPlan(String token, String name, int classes, long price) throws Exception {
        return createPlan(token, name, classes, price, "PERSONALIZED");
    }

    protected String createPlan(String token, String name, int classes, long price, String modality) throws Exception {
        MvcResult r = mvc.perform(withToken(post("/api/coach/plans"), token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"classesIncluded\":" + classes + ",\"priceCop\":" + price
                                + ",\"modality\":\"" + modality + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(json(r), "$.id");
    }

    /** Creates a student; returns the full JSON (student + inviteUrl). */
    protected String createStudentJson(String token, String name, String email) throws Exception {
        MvcResult r = mvc.perform(withToken(post("/api/coach/students"), token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"" + name + "\",\"email\":\"" + email + "\",\"whatsappPhone\":\"3001234567\",\"birthDate\":\"1990-05-01\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json(r);
    }

    protected String createStudent(String token, String name, String email) throws Exception {
        return JsonPath.read(createStudentJson(token, name, email), "$.student.id");
    }

    protected static String tokenFromInviteUrl(String inviteUrl) {
        return inviteUrl.substring(inviteUrl.lastIndexOf('/') + 1);
    }

    /** Registers a payment. The amount is mandatory in the API, so tests state 520.000 COP unless extraJson sets its own "amountCop". */
    protected MvcResult pay(String token, String studentId, String planId, String extraJson) throws Exception {
        String amount = extraJson.contains("amountCop") ? "" : ",\"amountCop\":520000";
        return mvc.perform(withToken(post("/api/coach/students/" + studentId + "/payments"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":\"" + planId + "\",\"method\":\"NEQUI\"" + amount + extraJson + "}"))
                .andReturn();
    }
}
