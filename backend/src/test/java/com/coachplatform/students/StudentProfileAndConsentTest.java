package com.coachplatform.students;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coachplatform.support.ApiIntegrationTest;
import com.coachplatform.support.ConsentFixtures;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Lote A over HTTP (H2, fixed clock 2026-10-06 12:00 Bogota): the profile with goal / birth date / guardian, the consents
 * recorded when an invitation is accepted, accepting and revoking them afterwards, and what a revocation causes.
 */
class StudentProfileAndConsentTest extends ApiIntegrationTest {

    private static final String GUARDIAN = "{\"name\":\"Marta Perez\",\"relationship\":\"madre\",\"phone\":\"3001234567\",\"email\":\"%s\"}";

    private record Created(String id, String inviteToken, String email) {
    }

    private ResultActions post(String path, String token, String body) throws Exception {
        return mvc.perform(withToken(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path), token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions put(String path, String token, String body) throws Exception {
        return mvc.perform(withToken(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path), token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions get(String path, String token) throws Exception {
        return mvc.perform(withToken(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path), token));
    }

    private Created adult(String coach, String name) throws Exception {
        String email = uniqueEmail("adulto");
        String json = json(post("/api/coach/students", coach, "{\"fullName\":\"" + name + "\",\"email\":\"" + email
                + "\",\"birthDate\":\"1990-05-01\",\"whatsappPhone\":\"3111111111\"}").andExpect(status().isCreated()).andReturn());
        return new Created(JsonPath.read(json, "$.student.id"), tokenFromInviteUrl(JsonPath.read(json, "$.inviteUrl")), email);
    }

    private Created minor(String coach, String name) throws Exception {
        return minor(coach, name, "2010-05-01");
    }

    private Created minor(String coach, String name, String birth) throws Exception {
        String guardianEmail = uniqueEmail("madre");
        String json = json(post("/api/coach/students", coach, "{\"fullName\":\"" + name + "\",\"birthDate\":\"" + birth + "\",\"goal\":\"Bajar de peso\","
                + "\"guardian\":" + GUARDIAN.formatted(guardianEmail) + "}").andExpect(status().isCreated()).andReturn());
        return new Created(JsonPath.read(json, "$.student.id"), tokenFromInviteUrl(JsonPath.read(json, "$.inviteUrl")), guardianEmail);
    }

    private ResultActions accept(Created c, boolean guardian, boolean whatsapp) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                .content(ConsentFixtures.acceptJson(c.inviteToken(), PASSWORD, guardian, whatsapp)));
    }

    private String acceptAndLogin(Created c, boolean guardian, boolean whatsapp) throws Exception {
        accept(c, guardian, whatsapp).andExpect(status().isOk());
        return login(c.email(), PASSWORD);
    }

    private List<Map<String, Object>> consentRows(String studentId) {
        return jdbc.queryForList("select type, version, text_sha256, signer_name, signer_relationship, accepted_at, accepted_by_user_id "
                + "from consent_record where student_id = ? order by type", UUID.fromString(studentId));
    }

    // ================================================================= profile

    @Test
    void aMinorNeedsTheCompleteGuardianAndTakesItsEmailAsTheLogin() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        post("/api/coach/students", coach, "{\"fullName\":\"Menor\",\"birthDate\":\"2010-05-01\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GUARDIAN_REQUIRED"));
        post("/api/coach/students", coach, "{\"fullName\":\"Menor\",\"birthDate\":\"2010-05-01\",\"guardian\":{\"name\":\"Marta\",\"phone\":\"300\"}}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("GUARDIAN_INCOMPLETE"));
        post("/api/coach/students", coach, "{\"fullName\":\"Futuro\",\"email\":\"f@test.co\",\"birthDate\":\"2026-10-07\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_BIRTH_DATE"));

        String json = json(post("/api/coach/students", coach, "{\"fullName\":\"Menor\",\"email\":\"ignorado@test.co\",\"birthDate\":\"2010-05-01\","
                + "\"goal\":\"Bajar de peso\",\"guardian\":" + GUARDIAN.formatted("MARTA@Test.co") + "}").andExpect(status().isCreated()).andReturn());
        assertThat((String) JsonPath.read(json, "$.student.email")).isEqualTo("marta@test.co");   // the guardian's, lowercase; the input email is ignored
        assertThat((String) JsonPath.read(json, "$.student.goal")).isEqualTo("Bajar de peso");
        assertThat((String) JsonPath.read(json, "$.student.audience")).isEqualTo("GUARDIAN");
        assertThat((Boolean) JsonPath.read(json, "$.student.minor")).isTrue();
        assertThat((String) JsonPath.read(json, "$.student.turnsAdultOn")).isEqualTo("2028-05-01");
        assertThat((String) JsonPath.read(json, "$.student.guardian.name")).isEqualTo("Marta Perez");
    }

    @Test
    void anAdultNeedsNoGuardian() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        Created ana = adult(coach, "Ana");
        get("/api/coach/students/" + ana.id(), coach).andExpect(jsonPath("$.audience").value("ADULT")).andExpect(jsonPath("$.minor").value(false))
                .andExpect(jsonPath("$.guardian").doesNotExist()).andExpect(jsonPath("$.ageAlert").value("NONE"));
    }

    @Test
    void theCoachSeesWhenAMinorIsAboutToTurnEighteen() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        Created soon = minor(coach, "Casi", "2008-12-05");       // 18 on 2026-12-05 = 60 days after 2026-10-06
        Created later = minor(coach, "Lejos", "2008-12-06");     // 61 days
        get("/api/coach/students/" + soon.id(), coach).andExpect(jsonPath("$.ageAlert").value("TURNS_ADULT_SOON"))
                .andExpect(jsonPath("$.turnsAdultOn").value("2026-12-05")).andExpect(jsonPath("$.daysUntilAdult").value(60));
        get("/api/coach/students/" + later.id(), coach).andExpect(jsonPath("$.ageAlert").value("NONE")).andExpect(jsonPath("$.daysUntilAdult").value(61));
    }

    @Test
    void anAdultWhoOnlyHasTheGuardiansAuthorizationIsFlaggedAfterTurningEighteen() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        Created kid = minor(coach, "Casi adulto", "2008-12-05");
        acceptAndLogin(kid, true, false);
        clock.set(java.time.Instant.parse("2026-12-06T17:00:00Z"));
        get("/api/coach/students/" + kid.id(), coach).andExpect(jsonPath("$.minor").value(false))
                .andExpect(jsonPath("$.ageAlert").value("TURNED_ADULT_NEEDS_AUTHORIZATION")).andExpect(jsonPath("$.audience").value("ADULT"));
    }

    @Test
    void theBirthDateCannotMoveTheStudentBetweenAdultAndMinorOnceTheInvitationWasAccepted() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        Created ana = adult(coach, "Ana");
        Created kid = minor(coach, "Nico");
        // before accepting, either direction is fine
        put("/api/coach/students/" + ana.id(), coach, "{\"data\":{\"fullName\":\"Ana\",\"email\":\"" + ana.email() + "\",\"birthDate\":\"1991-05-01\"}}")
                .andExpect(status().isOk());
        acceptAndLogin(ana, false, false);
        acceptAndLogin(kid, true, false);
        // adult -> minor
        put("/api/coach/students/" + ana.id(), coach, "{\"data\":{\"fullName\":\"Ana\",\"email\":\"" + ana.email() + "\",\"birthDate\":\"2012-05-01\","
                + "\"guardian\":" + GUARDIAN.formatted(uniqueEmail("m")) + "}}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUDIENCE_CHANGE_BLOCKED"));
        // minor -> adult
        put("/api/coach/students/" + kid.id(), coach, "{\"data\":{\"fullName\":\"Nico\",\"email\":\"nico@test.co\",\"birthDate\":\"1990-05-01\","
                + "\"guardian\":" + GUARDIAN.formatted(kid.email()) + "}}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUDIENCE_CHANGE_BLOCKED"));
        // staying inside the same modality is fine
        put("/api/coach/students/" + kid.id(), coach, "{\"data\":{\"fullName\":\"Nico\",\"birthDate\":\"2009-05-01\",\"guardian\":"
                + GUARDIAN.formatted(kid.email()) + "}}").andExpect(status().isOk());
    }

    @Test
    void aGuardianWithTwoMinorsOfTheSameCoachHitsTheEmailRuleAtCreationTime() throws Exception {
        // KNOWN LIMITATION (documented in CLAUDE.md): the guardian's email is the login of each minor and is unique per coach
        String coach = registerCoach(uniqueEmail("coach"));
        String sharedEmail = uniqueEmail("madre");
        String guardian = "\"guardian\":" + GUARDIAN.formatted(sharedEmail);
        post("/api/coach/students", coach, "{\"fullName\":\"Hijo 1\",\"birthDate\":\"2010-05-01\"," + guardian + "}").andExpect(status().isCreated());
        post("/api/coach/students", coach, "{\"fullName\":\"Hijo 2\",\"birthDate\":\"2011-05-01\"," + guardian + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STUDENT_EMAIL_EXISTS"));
    }

    @Test
    void aGuardianAlreadyHoldingAnAccountWithAnotherCoachFailsWhenAcceptingTheInvitationWithoutConsumingIt() throws Exception {
        String a = registerCoach(uniqueEmail("coach-a"));
        String b = registerCoach(uniqueEmail("coach-b"));
        String sharedEmail = uniqueEmail("madre");
        String body = "\"birthDate\":\"2010-05-01\",\"guardian\":" + GUARDIAN.formatted(sharedEmail) + "}";
        String jsonA = json(post("/api/coach/students", a, "{\"fullName\":\"Hijo A\"," + body).andExpect(status().isCreated()).andReturn());
        String jsonB = json(post("/api/coach/students", b, "{\"fullName\":\"Hijo B\"," + body).andExpect(status().isCreated()).andReturn());   // creating is fine: unique per coach
        accept(new Created(JsonPath.read(jsonA, "$.student.id"), tokenFromInviteUrl(JsonPath.read(jsonA, "$.inviteUrl")), sharedEmail), true, false)
                .andExpect(status().isOk());
        accept(new Created(JsonPath.read(jsonB, "$.student.id"), tokenFromInviteUrl(JsonPath.read(jsonB, "$.inviteUrl")), sharedEmail), true, false)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_USED"));
    }

    // ================================================================= the invitation: preview and acceptance

    @Test
    void thePreviewOffersTheTextsOfTheRightAudienceWithTheirVariablesFilled() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        Created kid = minor(coach, "Nico");
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/invitations/preview").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + kid.inviteToken() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.audience").value("GUARDIAN")).andExpect(jsonPath("$.guardianName").value("Marta Perez"))
                .andExpect(jsonPath("$.consents[0].type").value("DATA_GUARDIAN")).andExpect(jsonPath("$.consents[0].required").value(true))
                .andExpect(jsonPath("$.consents[0].version").value(ConsentFixtures.version("DATA_GUARDIAN")))
                .andExpect(jsonPath("$.consents[1].type").value("WHATSAPP")).andExpect(jsonPath("$.consents[1].required").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("{{").contains("Nico");     // the minor's name was filled into the guardian text
        assertThat(JsonPath.<String>read(body, "$.consents[1].bodyMarkdown")).contains("3001234567");   // WhatsApp goes to the guardian's phone

        Created ana = adult(coach, "Ana");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/invitations/preview").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + ana.inviteToken() + "\"}"))
                .andExpect(jsonPath("$.audience").value("ADULT")).andExpect(jsonPath("$.guardianName").doesNotExist())
                .andExpect(jsonPath("$.consents[0].type").value("DATA_ADULT")).andExpect(jsonPath("$.consents[1].type").value("WHATSAPP"));
    }

    @Test
    void aHostileStudentNameCannotInjectMarkupIntoTheTextTheGuardianReads() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        String evil = "<script>alert(1)<\\/script> [click](http://evil.example) *x*";
        String json = json(post("/api/coach/students", coach, "{\"fullName\":\"" + evil + "\",\"birthDate\":\"2010-05-01\",\"guardian\":"
                + GUARDIAN.formatted(uniqueEmail("m")) + "}").andExpect(status().isCreated()).andReturn());
        String token = tokenFromInviteUrl(JsonPath.read(json, "$.inviteUrl"));
        String body = JsonPath.read(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/invitations/preview").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.consents[0].bodyMarkdown");
        assertThat(body).doesNotContain("<script>").doesNotContain("](http").contains("\\<script\\>").doesNotContain("{{");
    }

    @Test
    void acceptingRecordsTheGuardiansAuthorizationWithTheSignerTheHashAndTheFixedClock() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        Created kid = minor(coach, "Nico");
        accept(kid, true, true).andExpect(status().isOk());
        var rows = consentRows(kid.id());
        assertThat(rows).hasSize(2);
        var guardianRow = rows.stream().filter(r -> r.get("TYPE").equals("DATA_GUARDIAN")).findFirst().orElseThrow();
        assertThat(guardianRow.get("SIGNER_NAME")).isEqualTo("Marta Perez");
        assertThat(guardianRow.get("SIGNER_RELATIONSHIP")).isEqualTo("madre");
        assertThat(guardianRow.get("VERSION")).isEqualTo(ConsentFixtures.version("DATA_GUARDIAN"));
        assertThat((String) guardianRow.get("TEXT_SHA256")).matches("[0-9a-f]{64}");
        assertThat(guardianRow.get("ACCEPTED_BY_USER_ID")).isEqualTo(jdbc.queryForObject("select id from app_user where email = ?", UUID.class, kid.email()));
        // the dates come from the injected clock (fixed at 2026-10-06 12:00 Bogota), not from the database
        assertThat(((java.time.OffsetDateTime) guardianRow.get("ACCEPTED_AT")).toInstant()).isEqualTo(clock.instant());
        var whatsapp = rows.stream().filter(r -> r.get("TYPE").equals("WHATSAPP")).findFirst().orElseThrow();
        assertThat(whatsapp.get("SIGNER_NAME")).isNull();
        assertThat(jdbc.queryForObject("select whatsapp_opt_in_at is not null and data_consent_at is not null from student where id = ?", Boolean.class,
                UUID.fromString(kid.id()))).isTrue();
    }

    @Test
    void anAdultWhoDeclinesWhatsappStillCreatesTheAccountWithOnlyTheDataAuthorization() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        Created ana = adult(coach, "Ana");
        accept(ana, false, false).andExpect(status().isOk());
        assertThat(consentRows(ana.id())).extracting(r -> r.get("TYPE")).containsExactly("DATA_ADULT");
        assertThat(jdbc.queryForObject("select whatsapp_opt_in_at is null from student where id = ?", Boolean.class, UUID.fromString(ana.id()))).isTrue();
    }

    @Test
    void aStaleVersionOrADeclinedAuthorizationFailsWithoutConsumingTheInvitation() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        Created ana = adult(coach, "Ana");
        String t = ana.inviteToken();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/invitations/accept").contentType(MediaType.APPLICATION_JSON).content(
                "{\"token\":\"" + t + "\",\"password\":\"" + PASSWORD + "\",\"acceptData\":true,\"dataVersion\":\"vieja\",\"acceptWhatsapp\":false,\"whatsappVersion\":null}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONSENT_VERSION_MISMATCH"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/invitations/accept").contentType(MediaType.APPLICATION_JSON).content(
                "{\"token\":\"" + t + "\",\"password\":\"" + PASSWORD + "\",\"acceptData\":false,\"dataVersion\":null,\"acceptWhatsapp\":false,\"whatsappVersion\":null}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DATA_CONSENT_REQUIRED"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/invitations/accept").contentType(MediaType.APPLICATION_JSON).content(
                "{\"token\":\"" + t + "\",\"password\":\"" + PASSWORD + "\",\"acceptData\":true,\"dataVersion\":\"" + ConsentFixtures.version("DATA_ADULT")
                        + "\",\"acceptWhatsapp\":true,\"whatsappVersion\":\"vieja\"}")).andExpect(status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/invitations/accept").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + t + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());   // the booleans are mandatory
        assertThat(consentRows(ana.id())).isEmpty();
        accept(ana, false, false).andExpect(status().isOk());     // the invitation was never consumed
    }

    // ================================================================= accepting and revoking afterwards

    @Test
    void theStudentSeesTheStateOfTheirConsents() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        Created ana = adult(coach, "Ana");
        String token = acceptAndLogin(ana, false, true);
        get("/api/student/consents", token).andExpect(status().isOk())
                .andExpect(jsonPath("$.audience").value("ADULT"))
                .andExpect(jsonPath("$.consents[?(@.type=='DATA_ADULT')].active").value(true))
                .andExpect(jsonPath("$.consents[?(@.type=='DATA_ADULT')].upToDate").value(true))
                .andExpect(jsonPath("$.consents[?(@.type=='WHATSAPP')].active").value(true))
                .andExpect(jsonPath("$.consents[?(@.type=='DATA_GUARDIAN')]").isEmpty());   // does not apply to an adult
    }

    @Test
    void revokingAndAcceptingWhatsappFollowsTheLedger() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        Created ana = adult(coach, "Ana");
        String token = acceptAndLogin(ana, false, true);
        String v = ConsentFixtures.version("WHATSAPP");
        post("/api/student/consents/WHATSAPP/accept", token, "{\"version\":\"" + v + "\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSENT_ALREADY_ACTIVE"));
        post("/api/student/consents/WHATSAPP/revoke", token, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.consents[?(@.type=='WHATSAPP')].active").value(false));
        post("/api/student/consents/WHATSAPP/revoke", token, "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONSENT_NOT_ACTIVE"));
        assertThat(jdbc.queryForObject("select whatsapp_opt_in_at is null from student where id = ?", Boolean.class, UUID.fromString(ana.id()))).isTrue();
        // revoking WHATSAPP never suspends the account
        login(ana.email(), PASSWORD);
        get("/api/coach/students/" + ana.id(), coach).andExpect(jsonPath("$.anonymizationRequested").value(false)).andExpect(jsonPath("$.active").value(true));
        post("/api/student/consents/WHATSAPP/accept", token, "{\"version\":\"vieja\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSENT_VERSION_MISMATCH"));
        clock.advance(java.time.Duration.ofDays(1));
        post("/api/student/consents/WHATSAPP/accept", token, "{\"version\":\"" + v + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.consents[?(@.type=='WHATSAPP')].active").value(true));
        // the full history is kept: two acceptances of the same version, one revocation
        assertThat(jdbc.queryForObject("select count(*) from consent_record where student_id = ? and type = 'WHATSAPP'", Integer.class, UUID.fromString(ana.id()))).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from consent_revocation where student_id = ?", Integer.class, UUID.fromString(ana.id()))).isEqualTo(1);
    }

    @Test
    void revokingTheDataAuthorizationSuspendsTheAccountAndMarksItForAnonymizationKeepingEveryRecord() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        Created ana = adult(coach, "Ana");
        String token = acceptAndLogin(ana, false, true);
        post("/api/student/consents/DATA_ADULT/revoke", token, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.anonymizationRequested").value(true));
        // the login is gone and the token already issued stops working
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + ana.email() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        get("/api/student/consents", token).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
        // the coach sees the mark; nothing was deleted
        get("/api/coach/students/" + ana.id(), coach).andExpect(jsonPath("$.anonymizationRequested").value(true)).andExpect(jsonPath("$.active").value(false));
        assertThat(consentRows(ana.id())).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from consent_revocation where student_id = ?", Integer.class, UUID.fromString(ana.id()))).isEqualTo(1);
        // and a data authorization can no longer be given
        post("/api/coach/students/" + ana.id() + "/consents/DATA_GUARDIAN/accept", coach, "{\"version\":\"" + ConsentFixtures.version("DATA_GUARDIAN") + "\"}")
                .andExpect(status().isUnprocessableEntity());   // does not apply to an adult
    }

    @Test
    void aGuardianAuthorizationCannotBeGivenFromAStudentSessionAndTheCoachRegistersIt() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        Created kid = minor(coach, "Nico");
        String v = ConsentFixtures.version("DATA_GUARDIAN");
        String token = acceptAndLogin(kid, true, true);       // the guardian holds this STUDENT account
        post("/api/student/consents/DATA_GUARDIAN/accept", token, "{\"version\":\"" + v + "\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GUARDIAN_CONSENT_NOT_ALLOWED"));
        post("/api/student/consents/DATA_ADULT/accept", token, "{\"version\":\"x\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONSENT_NOT_APPLICABLE"));
        // revoke it (the guardian asks), then the coach registers the physical authorization they hold
        post("/api/coach/students/" + kid.id() + "/consents/DATA_GUARDIAN/revoke", coach, "{}").andExpect(status().isOk());
        post("/api/coach/students/" + kid.id() + "/consents/DATA_GUARDIAN/accept", coach, "{\"version\":\"" + v + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ANONYMIZATION_PENDING"));   // the revocation suspended the account
    }

    @Test
    void theCoachRegistersTheGuardiansAuthorizationOfAStudentWhoHasNoneYetAndIsRecordedAsTheAccepter() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        Created kid = minor(coach, "Nico");
        String v = ConsentFixtures.version("DATA_GUARDIAN");
        post("/api/coach/students/" + kid.id() + "/consents/DATA_GUARDIAN/accept", coach, "{\"version\":\"" + v + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.consents[?(@.type=='DATA_GUARDIAN')].active").value(true));
        var row = consentRows(kid.id()).get(0);
        assertThat(row.get("SIGNER_NAME")).isEqualTo("Marta Perez");
        assertThat(row.get("ACCEPTED_BY_USER_ID")).isEqualTo(jdbc.queryForObject("select id from app_user where email = (select email from app_user where role = 'COACH' "
                + "and coach_id = (select coach_id from student where id = ?))", UUID.class, UUID.fromString(kid.id())));
        post("/api/coach/students/" + kid.id() + "/consents/WHATSAPP/accept", coach, "{\"version\":\"" + ConsentFixtures.version("WHATSAPP") + "\"}")
                .andExpect(status().isForbidden());       // the coach cannot consent to WhatsApp for the guardian
    }

    @Test
    void theCoachCannotRegisterAnAdultAuthorization() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        Created ana = adult(coach, "Ana");
        post("/api/coach/students/" + ana.id() + "/consents/DATA_ADULT/accept", coach, "{\"version\":\"" + ConsentFixtures.version("DATA_ADULT") + "\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADULT_CONSENT_NOT_ALLOWED"));
    }

    @Test
    void aGuardianHeldAccountCannotGiveTheAdultAuthorizationEvenAfterTheStudentTurnsEighteen() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        Created kid = minor(coach, "Casi adulto", "2008-12-05");
        String token = acceptAndLogin(kid, true, false);
        clock.set(java.time.Instant.parse("2026-12-06T17:00:00Z"));
        post("/api/student/consents/DATA_ADULT/accept", token, "{\"version\":\"" + ConsentFixtures.version("DATA_ADULT") + "\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADULT_CONSENT_NOT_ALLOWED"));
    }

    @Test
    void theCoachRecordsARevocationAndItCarriesTheCoachAsTheActor() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        Created ana = adult(coach, "Ana");
        acceptAndLogin(ana, false, true);
        post("/api/coach/students/" + ana.id() + "/consents/WHATSAPP/revoke", coach, "{}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select u.role from consent_revocation r join app_user u on u.id = r.revoked_by_user_id where r.student_id = ?",
                String.class, UUID.fromString(ana.id()))).isEqualTo("COACH");
        assertThat(jdbc.queryForObject("select revoked_at from consent_revocation where student_id = ?", java.sql.Timestamp.class,
                UUID.fromString(ana.id())).toInstant()).isEqualTo(clock.instant());
    }

    @Test
    void aCoachCannotSeeOrChangeTheConsentsOfAnotherCoachsStudent() throws Exception {
        String a = registerCoach(uniqueEmail("coach-a"));
        String b = registerCoach(uniqueEmail("coach-b"));
        Created ana = adult(a, "Ana");
        acceptAndLogin(ana, false, true);
        get("/api/coach/students/" + ana.id() + "/consents", b).andExpect(status().isNotFound());
        post("/api/coach/students/" + ana.id() + "/consents/WHATSAPP/revoke", b, "{}").andExpect(status().isNotFound());
        post("/api/coach/students/" + ana.id() + "/consents/DATA_GUARDIAN/accept", b, "{\"version\":\"x\"}").andExpect(status().isNotFound());
        get("/api/coach/students/" + ana.id() + "/consents", a).andExpect(status().isOk());
    }

    @Test
    void aStudentOnlyEverReachesTheirOwnConsents() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        Created ana = adult(coach, "Ana");
        Created beto = adult(coach, "Beto");
        String anaToken = acceptAndLogin(ana, false, true);
        acceptAndLogin(beto, false, true);
        // the student endpoints take no student id: revoking changes Ana only
        post("/api/student/consents/WHATSAPP/revoke", anaToken, "{}").andExpect(status().isOk());
        get("/api/coach/students/" + beto.id() + "/consents", coach).andExpect(jsonPath("$.consents[?(@.type=='WHATSAPP')].active").value(true));
        get("/api/coach/students/" + ana.id() + "/consents", coach).andExpect(jsonPath("$.consents[?(@.type=='WHATSAPP')].active").value(false));
        get("/api/coach/students/" + ana.id() + "/consents", anaToken).andExpect(status().isForbidden());   // coach endpoint, student role
    }

    // ================================================================= coach settings

    @Test
    void theNewSettingsHaveDefaultsValidatedRangesAndTheGymConsentDateIsSetByTheServer() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        get("/api/coach/settings", coach).andExpect(jsonPath("$.confirmationWindowHours").value(72)).andExpect(jsonPath("$.qrOpenMinutesBefore").value(15))
                .andExpect(jsonPath("$.qrCloseHoursAfterEnd").value(2)).andExpect(jsonPath("$.gymConsentConfirmed").value(false))
                .andExpect(jsonPath("$.gymConsentConfirmedAt").doesNotExist());
        String base = "\"cancelWindowHours\":2,\"classDurationMinutes\":60,\"expiringSoonDays\":5,\"expiringSoonClasses\":1,\"maxExtensionDays\":60,\"defaultGroupCapacity\":4,";
        put("/api/coach/settings", coach, "{" + base + "\"confirmationWindowHours\":96,\"qrOpenMinutesBefore\":30,\"qrCloseHoursAfterEnd\":3,\"gymConsentConfirmed\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmationWindowHours").value(96)).andExpect(jsonPath("$.gymConsentConfirmed").value(true))
                .andExpect(jsonPath("$.gymConsentConfirmedAt").value(clock.instant().toString()));
        clock.advance(java.time.Duration.ofDays(3));
        put("/api/coach/settings", coach, "{" + base + "\"confirmationWindowHours\":96,\"qrOpenMinutesBefore\":30,\"qrCloseHoursAfterEnd\":3,\"gymConsentConfirmed\":true}")
                .andExpect(jsonPath("$.gymConsentConfirmedAt").value(clock.instant().minus(java.time.Duration.ofDays(3)).toString()));   // kept
        put("/api/coach/settings", coach, "{" + base + "\"confirmationWindowHours\":96,\"qrOpenMinutesBefore\":30,\"qrCloseHoursAfterEnd\":3,\"gymConsentConfirmed\":false}")
                .andExpect(jsonPath("$.gymConsentConfirmed").value(false)).andExpect(jsonPath("$.gymConsentConfirmedAt").doesNotExist());
        for (String bad : new String[] {"\"confirmationWindowHours\":0,\"qrOpenMinutesBefore\":15,\"qrCloseHoursAfterEnd\":2",
                "\"confirmationWindowHours\":721,\"qrOpenMinutesBefore\":15,\"qrCloseHoursAfterEnd\":2",
                "\"confirmationWindowHours\":72,\"qrOpenMinutesBefore\":-1,\"qrCloseHoursAfterEnd\":2",
                "\"confirmationWindowHours\":72,\"qrOpenMinutesBefore\":121,\"qrCloseHoursAfterEnd\":2",
                "\"confirmationWindowHours\":72,\"qrOpenMinutesBefore\":15,\"qrCloseHoursAfterEnd\":25"}) {
            put("/api/coach/settings", coach, "{" + base + bad + ",\"gymConsentConfirmed\":false}").andExpect(status().isBadRequest());
        }
        // the limits themselves are valid
        put("/api/coach/settings", coach, "{" + base + "\"confirmationWindowHours\":720,\"qrOpenMinutesBefore\":120,\"qrCloseHoursAfterEnd\":24,\"gymConsentConfirmed\":false}")
                .andExpect(status().isOk());
        put("/api/coach/settings", coach, "{" + base + "\"confirmationWindowHours\":1,\"qrOpenMinutesBefore\":0,\"qrCloseHoursAfterEnd\":0,\"gymConsentConfirmed\":false}")
                .andExpect(status().isOk());
    }
}
