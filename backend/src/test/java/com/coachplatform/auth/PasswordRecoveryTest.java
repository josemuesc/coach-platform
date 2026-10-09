package com.coachplatform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coachplatform.support.ApiIntegrationTest;
import com.coachplatform.support.ConsentFixtures;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Password recovery through the coach's one-time link, session invalidation, the audit and the password policy. */
class PasswordRecoveryTest extends ApiIntegrationTest {

    private static final java.util.concurrent.atomic.AtomicInteger addresses = new java.util.concurrent.atomic.AtomicInteger();
    private static final String NEW_PASSWORD = "Otra-clave-segura-9";

    private record Account(String coachToken, String studentId, String email, String studentToken) {
    }

    private Account account() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        return accountOf(coach);
    }

    private Account accountOf(String coachToken) throws Exception {
        String email = uniqueEmail("alumno");
        String created = createStudentJson(coachToken, "Ana", email);
        String invite = tokenFromInviteUrl(JsonPath.read(created, "$.inviteUrl"));
        mvc.perform(post("/api/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                .content(ConsentFixtures.acceptJson(invite, PASSWORD))).andExpect(status().isOk());
        return new Account(coachToken, JsonPath.read(created, "$.student.id"), email, login(email, PASSWORD));
    }

    private String issue(Account a) throws Exception {
        clock.advance(Duration.ofSeconds(1));   // audit lines written in the same instant have no defined order
        MvcResult r = mvc.perform(withToken(post("/api/coach/students/" + a.studentId() + "/password-reset"), a.coachToken()))
                .andExpect(status().isCreated()).andReturn();
        String url = JsonPath.read(json(r), "$.resetUrl");
        assertThat(url).contains("/reset/");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private MvcResult redeem(String token, String password) throws Exception {
        int n = addresses.incrementAndGet();   // each call from its own address: the per-IP throttle is not what these tests are about
        return mvc.perform(fromIp(post("/api/auth/reset-password"), "10.77." + (n / 250) + "." + (n % 250 + 1))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + password + "\"}")).andReturn();
    }

    /** Lines written in the same instant are ordered logically (the id is random): a replaced link is REVOKED, then the new one CREATED. */
    private List<String> accountEvents(String email) {
        return jdbc.queryForList("select a.event from account_audit a join app_user u on u.id = a.target_user_id "
                + "where lower(u.email) = ? order by a.occurred_at, "
                + "case a.event when 'RESET_LINK_REVOKED' then 1 when 'RESET_LINK_CREATED' then 2 else 3 end", String.class, email);
    }

    private MvcResult attemptLogin(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    // ================================================================= the reset link

    @Test
    void theCoachsLinkSetsANewPasswordAndEndsTheOldSessions() throws Exception {
        Account a = account();
        String token = issue(a);

        assertThat(redeem(token, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);

        assertThat(attemptLogin(a.email(), PASSWORD).getResponse().getStatus()).isEqualTo(401);
        String fresh = login(a.email(), NEW_PASSWORD);
        mvc.perform(withToken(get("/api/me"), fresh)).andExpect(status().isOk());
        MvcResult stale = mvc.perform(withToken(get("/api/me"), a.studentToken())).andReturn();
        assertThat(stale.getResponse().getStatus()).isEqualTo(401);
        assertThat(JsonPath.<String>read(json(stale), "$.code")).isEqualTo("INVALID_SESSION");
    }

    @Test
    void meTellsWhenAndHowThePasswordLastChanged() throws Exception {
        Account a = account();
        mvc.perform(withToken(get("/api/me"), a.studentToken())).andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangedAt").doesNotExist()).andExpect(jsonPath("$.passwordChangedBy").doesNotExist());

        redeem(issue(a), NEW_PASSWORD);
        String afterLink = login(a.email(), NEW_PASSWORD);
        mvc.perform(withToken(get("/api/me"), afterLink)).andExpect(jsonPath("$.passwordChangedBy").value("COACH_LINK"))
                .andExpect(jsonPath("$.passwordChangedAt").exists());

        mvc.perform(withToken(post("/api/auth/change-password"), afterLink).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + NEW_PASSWORD + "\",\"newPassword\":\"Tercera-clave-123\"}")).andExpect(status().isOk());
        mvc.perform(withToken(get("/api/me"), login(a.email(), "Tercera-clave-123"))).andExpect(jsonPath("$.passwordChangedBy").value("SELF"));
    }

    @Test
    void aLinkWorksExactlyOnce() throws Exception {
        Account a = account();
        String token = issue(a);
        assertThat(redeem(token, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);
        MvcResult again = redeem(token, "Y-otra-clave-mas-1");
        assertThat(again.getResponse().getStatus()).isEqualTo(400);
        assertThat(JsonPath.<String>read(json(again), "$.code")).isEqualTo("INVALID_RESET_LINK");
        login(a.email(), NEW_PASSWORD);   // the second attempt changed nothing
    }

    @Test
    void unknownUsedRevokedAndExpiredLinksAreAnsweredIdentically() throws Exception {
        Account a = account();
        String used = issue(a);
        redeem(used, NEW_PASSWORD);
        String revoked = issue(a);
        mvc.perform(withToken(delete("/api/coach/students/" + a.studentId() + "/password-reset"), a.coachToken())).andExpect(status().isNoContent());
        String expired = issue(a);
        clock.advance(Duration.ofHours(24));

        MvcResult unknown = redeem("no-such-token-" + "x".repeat(30), "Clave-valida-123");
        for (String token : List.of(used, revoked, expired)) {
            MvcResult r = redeem(token, "Clave-valida-123");
            assertThat(r.getResponse().getStatus()).isEqualTo(unknown.getResponse().getStatus()).isEqualTo(400);
            assertThat(json(r)).isEqualTo(json(unknown));
            assertThat(r.getResponse().getHeaderNames()).containsExactlyInAnyOrderElementsOf(unknown.getResponse().getHeaderNames());
        }
    }

    @Test
    void aLinkIsValidForAlmost24HoursAndDeadAtExactly24() throws Exception {
        Account a = account();
        String token = issue(a);
        clock.advance(Duration.ofHours(24).minusSeconds(1));
        assertThat(redeem(token, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);

        Account b = account();
        String late = issue(b);
        clock.advance(Duration.ofHours(24));
        assertThat(redeem(late, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void issuingANewLinkKillsTheOldOneAndEverythingIsAudited() throws Exception {
        Account a = account();
        String first = issue(a);
        String second = issue(a);
        assertThat(redeem(first, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(400);
        assertThat(redeem(second, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);

        assertThat(accountEvents(a.email())).containsExactly("RESET_LINK_CREATED", "RESET_LINK_REVOKED", "RESET_LINK_CREATED", "RESET_LINK_USED");
        // who: the coach created and revoked, the account itself used it
        var rows = jdbc.queryForList("select a.event, a.actor_user_id = a.target_user_id as self from account_audit a "
                + "join app_user u on u.id = a.target_user_id where lower(u.email) = ?", a.email());
        rows.forEach(row -> assertThat((Boolean) row.get("self")).isEqualTo("RESET_LINK_USED".equals(row.get("event"))));
    }

    @Test
    void theTokenIsStoredOnlyHashedAndNeverInTheAudit() throws Exception {
        Account a = account();
        String token = issue(a);
        List<String> stored = jdbc.queryForList("select token_hash from password_reset", String.class);
        assertThat(stored).doesNotContain(token).allMatch(h -> h.length() == 64);
        assertThat(jdbc.queryForObject("select count(*) from password_reset where token_hash = ?", Integer.class,
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes())))).isEqualTo(1);
    }

    @Test
    void theRevokeEndpointKillsTheOpenLinkAndIsIdempotent() throws Exception {
        Account a = account();
        String token = issue(a);
        clock.advance(Duration.ofSeconds(1));
        mvc.perform(withToken(delete("/api/coach/students/" + a.studentId() + "/password-reset"), a.coachToken())).andExpect(status().isNoContent());
        mvc.perform(withToken(delete("/api/coach/students/" + a.studentId() + "/password-reset"), a.coachToken())).andExpect(status().isNoContent());
        assertThat(redeem(token, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(400);
        assertThat(accountEvents(a.email())).containsExactly("RESET_LINK_CREATED", "RESET_LINK_REVOKED");
    }

    @Test
    void aFailedRedemptionLeavesTheLinkUsable() throws Exception {
        Account a = account();
        String token = issue(a);
        assertThat(redeem(token, "corta").getResponse().getStatus()).isEqualTo(400);         // policy: rejected before anything is consumed
        assertThat(redeem(token, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void resettingClearsTheLoginLockoutOfThatAccount() throws Exception {
        Account a = account();
        for (int i = 0; i < 5; i++) {
            attemptLogin(a.email(), "wrong-guess-" + i);
        }
        assertThat(attemptLogin(a.email(), PASSWORD).getResponse().getStatus()).isEqualTo(429);
        redeem(issue(a), NEW_PASSWORD);
        assertThat(attemptLogin(a.email(), NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    // ================================================================= who may ask for it

    @Test
    void onlyTheOwningCoachCanGenerateALink() throws Exception {
        Account a = account();
        String otherCoach = registerCoach(uniqueEmail("otro"));
        mvc.perform(withToken(post("/api/coach/students/" + a.studentId() + "/password-reset"), otherCoach)).andExpect(status().isNotFound());
        mvc.perform(withToken(delete("/api/coach/students/" + a.studentId() + "/password-reset"), otherCoach)).andExpect(status().isNotFound());
        mvc.perform(withToken(post("/api/coach/students/" + a.studentId() + "/password-reset"), a.studentToken())).andExpect(status().isForbidden());
        mvc.perform(post("/api/coach/students/" + a.studentId() + "/password-reset")).andExpect(status().isUnauthorized());
    }

    @Test
    void aStudentWithoutAnAccountHasNothingToReset() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        String pending = createStudent(coach, "Sin cuenta", uniqueEmail("pendiente"));
        mvc.perform(withToken(post("/api/coach/students/" + pending + "/password-reset"), coach)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STUDENT_HAS_NO_ACCOUNT"));
    }

    @Test
    void aSuspendedAccountGetsNoLink() throws Exception {
        Account a = account();
        jdbc.update("update app_user set active = false where lower(email) = ?", a.email());
        mvc.perform(withToken(post("/api/coach/students/" + a.studentId() + "/password-reset"), a.coachToken())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
    }

    @Test
    void manyBadLinksFromOneAddressAreThrottled() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(fromIp(post("/api/auth/reset-password"), "10.9.0.1").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"token\":\"bad-" + i + "\",\"newPassword\":\"Clave-valida-123\"}")).andExpect(status().isBadRequest());
        }
        mvc.perform(fromIp(post("/api/auth/reset-password"), "10.9.0.1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"bad-x\",\"newPassword\":\"Clave-valida-123\"}")).andExpect(status().isTooManyRequests());
    }

    // ================================================================= changing it yourself

    @Test
    void changingYourPasswordReturnsANewTokenAndKillsTheOldOnes() throws Exception {
        Account a = account();
        String otherDevice = login(a.email(), PASSWORD);
        MvcResult changed = mvc.perform(withToken(post("/api/auth/change-password"), a.studentToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")).andExpect(status().isOk()).andReturn();
        String fresh = JsonPath.read(json(changed), "$.token");

        mvc.perform(withToken(get("/api/me"), fresh)).andExpect(status().isOk());
        mvc.perform(withToken(get("/api/me"), a.studentToken())).andExpect(status().isUnauthorized());
        mvc.perform(withToken(get("/api/me"), otherDevice)).andExpect(status().isUnauthorized());
        assertThat(accountEvents(a.email())).containsExactly("PASSWORD_CHANGED");
    }

    @Test
    void wrongCurrentPasswordsOnChangePasswordAreLimited() throws Exception {
        Account a = account();
        for (int i = 0; i < 5; i++) {
            mvc.perform(withToken(post("/api/auth/change-password"), a.studentToken()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"currentPassword\":\"wrong-" + i + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")).andExpect(status().isUnauthorized());
        }
        mvc.perform(withToken(post("/api/auth/change-password"), a.studentToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")).andExpect(status().isTooManyRequests());
    }

    // ================================================================= the 10-character policy, everywhere

    private static String nine() { return "123456789"; }

    @Test
    void theMinimumOfTenCharactersAppliesToRegistrationInvitationChangeAndReset() throws Exception {
        // registration
        mvc.perform(post("/api/auth/register-coach").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"C\",\"email\":\"" + uniqueEmail("p9") + "\",\"password\":\"" + nine() + "\"}")).andExpect(status().isBadRequest());
        // invitation
        String coach = registerCoach(uniqueEmail("coach"));
        String invite = tokenFromInviteUrl(JsonPath.read(createStudentJson(coach, "Beto", uniqueEmail("beto")), "$.inviteUrl"));
        mvc.perform(post("/api/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                .content(ConsentFixtures.acceptJson(invite, nine()))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                .content(ConsentFixtures.acceptJson(invite, "1234567890"))).andExpect(status().isOk());   // 10 is enough, and the link survived
        // change
        Account a = accountOf(coach);
        mvc.perform(withToken(post("/api/auth/change-password"), a.studentToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + nine() + "\"}")).andExpect(status().isBadRequest());
        // reset
        String token = issue(a);
        assertThat(redeem(token, nine()).getResponse().getStatus()).isEqualTo(400);
        assertThat(redeem(token, "1234567890").getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void aPasswordOverSeventyTwoBytesIsRejectedNotAnError() throws Exception {
        String emojis = "🔒".repeat(20);   // 20 characters, 80 bytes
        mvc.perform(post("/api/auth/register-coach").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"C\",\"email\":\"" + uniqueEmail("big") + "\",\"password\":\"" + emojis + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register-coach").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"C\",\"email\":\"" + uniqueEmail("ok") + "\",\"password\":\"" + "🔒".repeat(10) + "\"}")).andExpect(status().isCreated());
    }

    // ================================================================= login answers cannot be told apart

    @Test
    void unknownEmailWrongPasswordAndSuspendedAccountGiveTheSameAnswer() throws Exception {
        String known = uniqueEmail("known");
        registerCoach(known);
        Account suspended = account();
        jdbc.update("update app_user set active = false where lower(email) = ?", suspended.email());

        MvcResult unknown = attemptLogin(uniqueEmail("ghost"), "Clave-cualquiera-1");
        MvcResult wrong = attemptLogin(known, "Clave-cualquiera-1");
        MvcResult inactive = attemptLogin(suspended.email(), PASSWORD);   // the RIGHT password of a suspended account

        for (MvcResult r : List.of(wrong, inactive)) {
            assertThat(r.getResponse().getStatus()).isEqualTo(unknown.getResponse().getStatus()).isEqualTo(401);
            assertThat(json(r)).isEqualTo(json(unknown));
            assertThat(r.getResponse().getHeaderNames()).containsExactlyInAnyOrderElementsOf(unknown.getResponse().getHeaderNames());
            assertThat(r.getResponse().getContentType()).isEqualTo(unknown.getResponse().getContentType());
        }
    }
}
