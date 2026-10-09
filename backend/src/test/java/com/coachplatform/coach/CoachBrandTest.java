package com.coachplatform.coach;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coachplatform.support.ApiIntegrationTest;
import com.coachplatform.support.ConsentFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** The trainer's brand (name + #RRGGBB color): who sees it, who edits it, and that the server alone validates it. */
class CoachBrandTest extends ApiIntegrationTest {

    private void putBrand(String token, String body, int expected) throws Exception {
        mvc.perform(withToken(put("/api/coach/brand"), token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected));
    }

    private String brandJson(String name, String color) {
        return "{\"brandName\":\"" + name + "\",\"primaryColor\":" + (color == null ? "null" : "\"" + color + "\"") + "}";
    }

    private record Student(String token, String inviteToken) {
    }

    private String acceptedStudent(String coachToken) throws Exception {
        String email = uniqueEmail("alumno");
        String created = createStudentJson(coachToken, "Ana", email);
        String invite = tokenFromInviteUrl(JsonPath.read(created, "$.inviteUrl"));
        mvc.perform(post("/api/invitations/accept").contentType(MediaType.APPLICATION_JSON)
                .content(ConsentFixtures.acceptJson(invite, PASSWORD))).andExpect(status().isOk());
        return login(email, PASSWORD);
    }

    @Test
    void aNewCoachHasItsNameAsBrandAndNoColor() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        mvc.perform(withToken(get("/api/me"), coach)).andExpect(status().isOk())
                .andExpect(jsonPath("$.brandName").isNotEmpty()).andExpect(jsonPath("$.primaryColor").doesNotExist());
        mvc.perform(withToken(get("/api/coach/brand"), coach)).andExpect(status().isOk()).andExpect(jsonPath("$.primaryColor").doesNotExist());
    }

    @Test
    void theCoachChangesItAndTheColorIsStoredUpperCase() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        mvc.perform(withToken(put("/api/coach/brand"), coach).contentType(MediaType.APPLICATION_JSON).content(brandJson("  Laura Fit  ", "#0b6e5c")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.brandName").value("Laura Fit")).andExpect(jsonPath("$.primaryColor").value("#0B6E5C"));
        mvc.perform(withToken(get("/api/me"), coach)).andExpect(jsonPath("$.brandName").value("Laura Fit"))
                .andExpect(jsonPath("$.primaryColor").value("#0B6E5C"));
    }

    @Test
    void onlyAWellFormedColorAndNameAreAccepted() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        for (String bad : new String[] {"#FFF", "#GGGGGG", "0B6E5C", "red", "#0B6E5C0", "#0B6E5", " #0B6E5C", "rgb(1,2,3)", "javascript:1", "#0B6E5C;}"}) {
            putBrand(coach, brandJson("Laura", bad), 400);
        }
        putBrand(coach, brandJson("Laura", null), 400);                                   // the color is mandatory in the PUT
        putBrand(coach, "{\"brandName\":\"Laura\"}", 400);
        putBrand(coach, brandJson("   ", "#0B6E5C"), 400);
        putBrand(coach, brandJson("x".repeat(101), "#0B6E5C"), 400);
        putBrand(coach, brandJson("x".repeat(100), "#0B6E5C"), 200);
        mvc.perform(withToken(get("/api/coach/brand"), coach)).andExpect(jsonPath("$.primaryColor").value("#0B6E5C"));   // rejections changed nothing
    }

    @Test
    void aStudentSeesTheBrandOfItsOwnCoachAndCannotEditIt() throws Exception {
        String coachA = registerCoach(uniqueEmail("a"));
        String coachB = registerCoach(uniqueEmail("b"));
        putBrand(coachA, brandJson("Marca A", "#112233"), 200);
        putBrand(coachB, brandJson("Marca B", "#445566"), 200);
        String student = acceptedStudent(coachA);

        mvc.perform(withToken(get("/api/me"), student)).andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.brandName").value("Marca A")).andExpect(jsonPath("$.primaryColor").value("#112233"));
        putBrand(coachB, brandJson("Marca B2", "#778899"), 200);
        mvc.perform(withToken(get("/api/me"), student)).andExpect(jsonPath("$.brandName").value("Marca A"));   // another coach changes nothing here

        putBrand(student, brandJson("Hackeada", "#000000"), 403);
        mvc.perform(withToken(get("/api/coach/brand"), student)).andExpect(status().isForbidden());
        mvc.perform(put("/api/coach/brand").contentType(MediaType.APPLICATION_JSON).content(brandJson("x", "#000000"))).andExpect(status().isUnauthorized());
        mvc.perform(withToken(get("/api/me"), coachA)).andExpect(jsonPath("$.brandName").value("Marca A"));
    }

    @Test
    void theInvitationPreviewCarriesTheBrandSoThePageIsBrandedBeforeLogin() throws Exception {
        String coach = registerCoach(uniqueEmail("coach"));
        putBrand(coach, brandJson("Laura Fit", "#AA5500"), 200);
        String created = createStudentJson(coach, "Ana", uniqueEmail("alumno"));
        String invite = tokenFromInviteUrl(JsonPath.read(created, "$.inviteUrl"));

        String body = mvc.perform(post("/api/invitations/preview").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + invite + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.brandName")).isEqualTo("Laura Fit");
        assertThat(JsonPath.<String>read(body, "$.primaryColor")).isEqualTo("#AA5500");
    }
}
