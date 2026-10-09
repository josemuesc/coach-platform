package com.coachplatform.students;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coachplatform.support.ApiIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

@ExtendWith(OutputCaptureExtension.class)
class InvitationFlowTest extends ApiIntegrationTest {

    private static final String STUDENT_PASSWORD = "Mi-clave-segura-1";

    private ResultActions preview(String token, String ip) throws Exception {
        return mvc.perform(fromIp(post("/api/invitations/preview"), ip).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"));
    }

    private ResultActions accept(String token, String password, String ip) throws Exception {
        return mvc.perform(fromIp(post("/api/invitations/accept"), ip).contentType(MediaType.APPLICATION_JSON)
                .content(com.coachplatform.support.ConsentFixtures.acceptJson(token, password)));
    }

    private String inviteToken(String studentJson) {
        return tokenFromInviteUrl(JsonPath.read(studentJson, "$.inviteUrl"));
    }

    @Test
    void studentAcceptsTheInvitationChoosesAPasswordAndLogsIn() throws Exception {
        String coachToken = registerCoach(uniqueEmail("coach"));
        String studentEmail = uniqueEmail("alumno").toUpperCase(); // stored lowercase
        String studentJson = createStudentJson(coachToken, "Ana Pérez", studentEmail);
        String token = inviteToken(studentJson);
        assertThat(JsonPath.<String>read(studentJson, "$.student.email")).isEqualTo(studentEmail.toLowerCase());

        preview(token, "10.10.0.1").andExpect(status().isOk())
                .andExpect(jsonPath("$.studentName").value("Ana Pérez"))
                .andExpect(jsonPath("$.brandName").isNotEmpty());

        accept(token, STUDENT_PASSWORD, "10.10.0.1").andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(studentEmail.toLowerCase()));

        String studentToken = login(studentEmail.toLowerCase(), STUDENT_PASSWORD);
        mvc.perform(withToken(get("/api/me"), studentToken)).andExpect(jsonPath("$.role").value("STUDENT"));
        mvc.perform(withToken(get("/api/coach/plans"), studentToken)).andExpect(status().isForbidden());

        // the coach now sees the student with an account
        mvc.perform(withToken(get("/api/coach/students"), coachToken))
                .andExpect(jsonPath("$[0].hasAccount").value(true));
    }

    @Test
    void anInvitationWorksOnlyOnce() throws Exception {
        String coachToken = registerCoach(uniqueEmail("coach"));
        String token = inviteToken(createStudentJson(coachToken, "Luis", uniqueEmail("alumno")));

        accept(token, STUDENT_PASSWORD, "10.10.0.2").andExpect(status().isOk());
        accept(token, "Otra-clave-segura-2", "10.10.0.2").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INVITATION"));
        preview(token, "10.10.0.2").andExpect(status().isBadRequest());
    }

    @Test
    void anExpiredInvitationIsRejected() throws Exception {
        String coachToken = registerCoach(uniqueEmail("coach"));
        String token = inviteToken(createStudentJson(coachToken, "Marta", uniqueEmail("alumno")));

        clock.advance(Duration.ofDays(6).plusHours(23));
        preview(token, "10.10.0.3").andExpect(status().isOk()); // still valid just before 7 days

        clock.advance(Duration.ofHours(2));
        preview(token, "10.10.0.3").andExpect(status().isBadRequest());
        accept(token, STUDENT_PASSWORD, "10.10.0.3").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INVITATION"));
    }

    @Test
    void reissuingRevokesThePreviousLink() throws Exception {
        String coachToken = registerCoach(uniqueEmail("coach"));
        String studentJson = createStudentJson(coachToken, "Pedro", uniqueEmail("alumno"));
        String studentId = JsonPath.read(studentJson, "$.student.id");
        String oldToken = inviteToken(studentJson);

        String reissued = json(mvc.perform(withToken(post("/api/coach/students/" + studentId + "/invitations"), coachToken))
                .andExpect(status().isCreated()).andReturn());
        String newToken = inviteToken(reissued);

        assertThat(newToken).isNotEqualTo(oldToken);
        accept(oldToken, STUDENT_PASSWORD, "10.10.0.4").andExpect(status().isBadRequest());
        accept(newToken, STUDENT_PASSWORD, "10.10.0.4").andExpect(status().isOk());

        mvc.perform(withToken(post("/api/coach/students/" + studentId + "/invitations"), coachToken))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STUDENT_ALREADY_HAS_ACCOUNT"));
    }

    @Test
    void onlyTheHashOfTheTokenIsStored() throws Exception {
        String coachToken = registerCoach(uniqueEmail("coach"));
        String token = inviteToken(createStudentJson(coachToken, "Sofía", uniqueEmail("alumno")));

        var hashes = jdbc.queryForList("select token_hash from invitation", String.class);
        String expected = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(hashes).contains(expected).doesNotContain(token);
        assertThat(hashes).allMatch(h -> h.length() == 64);
    }

    @Test
    void passwordPolicyIsEnforcedAndAFailedAcceptDoesNotBurnTheInvitation() throws Exception {
        String coachToken = registerCoach(uniqueEmail("coach"));
        String token = inviteToken(createStudentJson(coachToken, "Nora", uniqueEmail("alumno")));

        accept(token, "corta123", "10.10.0.5").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        accept(token, STUDENT_PASSWORD, "10.10.0.5").andExpect(status().isOk());
    }

    @Test
    void ifTheEmailAlreadyHasAnAccountTheInvitationIsNotConsumed() throws Exception {
        String takenEmail = uniqueEmail("taken");
        registerCoach(takenEmail); // that email already owns an account (one email = one account platform-wide)
        String coachToken = registerCoach(uniqueEmail("coach"));
        String token = inviteToken(createStudentJson(coachToken, "Tomás", takenEmail));

        accept(token, STUDENT_PASSWORD, "10.10.0.6").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_USED"));
        preview(token, "10.10.0.6").andExpect(status().isOk());
    }

    @Test
    void unknownTokensAreRejectedWithTheSameAnswer() throws Exception {
        preview("no-such-token", "10.10.0.7").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INVITATION"));
        accept("no-such-token", STUDENT_PASSWORD, "10.10.0.7").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INVITATION"));
    }

    @Test
    void unknownUsedRevokedAndExpiredTokensAreIndistinguishable() throws Exception {
        String coachToken = registerCoach(uniqueEmail("coach"));

        String used = inviteToken(createStudentJson(coachToken, "Usada", uniqueEmail("alumno")));
        accept(used, STUDENT_PASSWORD, "10.20.0.1").andExpect(status().isOk());

        String revokedJson = createStudentJson(coachToken, "Revocada", uniqueEmail("alumno"));
        String revoked = inviteToken(revokedJson);
        mvc.perform(withToken(post("/api/coach/students/" + JsonPath.read(revokedJson, "$.student.id") + "/invitations"), coachToken))
                .andExpect(status().isCreated());

        String expired = inviteToken(createStudentJson(coachToken, "Vencida", uniqueEmail("alumno")));
        clock.advance(Duration.ofDays(8));

        java.util.Set<String> previewAnswers = new java.util.HashSet<>();
        java.util.Set<String> acceptAnswers = new java.util.HashSet<>();
        for (String token : java.util.List.of("never-issued-token", used, revoked, expired)) {
            var p = preview(token, "10.20.0.2").andReturn().getResponse();
            var a = accept(token, STUDENT_PASSWORD, "10.20.0.3").andReturn().getResponse();
            previewAnswers.add(p.getStatus() + " " + p.getContentAsString() + " " + p.getHeaderNames());
            acceptAnswers.add(a.getStatus() + " " + a.getContentAsString() + " " + a.getHeaderNames());
        }
        assertThat(previewAnswers).hasSize(1).first().asString().startsWith("400 {\"code\":\"INVALID_INVITATION\"}");
        assertThat(acceptAnswers).hasSize(1).first().asString().startsWith("400 {\"code\":\"INVALID_INVITATION\"}");
    }

    @Test
    void failedAttemptsAreThrottledPerIp() throws Exception {
        String ip = "10.10.9.9";
        for (int i = 0; i < 10; i++) {
            accept("guess-" + i, STUDENT_PASSWORD, ip).andExpect(status().isBadRequest());
        }
        accept("guess-final", STUDENT_PASSWORD, ip).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
        preview("guess-final", ip).andExpect(status().isTooManyRequests()); // preview shares the same bucket

        accept("guess-final", STUDENT_PASSWORD, "10.10.9.10").andExpect(status().isBadRequest()); // other IP unaffected
    }

    @Test
    void theTokenAndThePasswordNeverReachTheLogs(CapturedOutput output) throws Exception {
        String coachToken = registerCoach(uniqueEmail("coach"));
        String token = inviteToken(createStudentJson(coachToken, "Elena", uniqueEmail("alumno")));
        String secretPassword = "Clave-super-secreta-77";

        preview(token, "10.10.0.8").andExpect(status().isOk());
        accept(token, "corta", "10.10.0.8"); // validation failure path
        accept(token, secretPassword, "10.10.0.8").andExpect(status().isOk());
        accept(token, secretPassword, "10.10.0.8").andExpect(status().isBadRequest()); // reuse path
        preview("garbage-token-value-xyz", "10.10.0.8");

        assertThat(output.getAll())
                .doesNotContain(token)
                .doesNotContain(secretPassword)
                .doesNotContain("garbage-token-value-xyz");
    }

    @Test
    void recordsWithSecretsRedactToString() {
        var issued = new com.coachplatform.students.api.InvitationIssued("secret-token-123", java.time.Instant.EPOCH);
        assertThat(issued.toString()).doesNotContain("secret-token-123");
    }
}
