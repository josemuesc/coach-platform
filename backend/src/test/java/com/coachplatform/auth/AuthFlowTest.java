package com.coachplatform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coachplatform.auth.AuthDtos.AuthResponse;
import com.coachplatform.security.JwtService;
import com.coachplatform.security.UserRole;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowTest {

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwt;

    private static String json(String email, String pass) {
        return "{\"name\":\"Coach\",\"email\":\"" + email + "\",\"password\":\"" + pass + "\"}";
    }

    @Test
    void registerThenLoginThenMe() throws Exception {
        mvc.perform(post("/api/auth/register-coach").contentType(MediaType.APPLICATION_JSON)
                        .content(json("Coach1@Test.co", "password123")))
                .andExpect(status().isCreated());

        // email is case-insensitive and unique
        mvc.perform(post("/api/auth/register-coach").contentType(MediaType.APPLICATION_JSON)
                        .content(json("coach1@test.co", "password123")))
                .andExpect(status().isConflict());

        AuthResponse login = auth.login(new AuthDtos.LoginRequest("coach1@test.co", "password123"), "127.0.0.1");
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + login.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("COACH"))
                .andExpect(jsonPath("$.coachId").value(login.coachId().toString()));
    }

    @Test
    void wrongPasswordIs401AndNoTokenIs401() throws Exception {
        auth.registerCoach(new AuthDtos.RegisterCoachRequest("C", "c2@test.co", "password123"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"c2@test.co\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void shortPasswordIsRejected() throws Exception {
        mvc.perform(post("/api/auth/register-coach").contentType(MediaType.APPLICATION_JSON)
                        .content(json("c3@test.co", "short")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changePasswordRequiresCurrentPasswordAndReplacesIt() throws Exception {
        AuthResponse reg = auth.registerCoach(new AuthDtos.RegisterCoachRequest("C", "c4@test.co", "password123"));

        mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + reg.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong-pass\",\"newPassword\":\"new-password-2\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + reg.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"password123\",\"newPassword\":\"new-password-2\"}"))
                .andExpect(status().isOk());

        assertThat(auth.login(new AuthDtos.LoginRequest("c4@test.co", "new-password-2"), "127.0.0.1").token()).isNotBlank();
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.security.authentication.BadCredentialsException.class,
                () -> auth.login(new AuthDtos.LoginRequest("c4@test.co", "password123"), "127.0.0.1"));
    }

    @Test
    void studentCannotUseCoachEndpoints() throws Exception {
        AppUser student = users.save(new AppUser(UUID.randomUUID(), "s2@test.co", encoder.encode("password123"), UserRole.STUDENT));
        mvc.perform(get("/api/coach/anything").header("Authorization", "Bearer " + jwt.issue(student.getId(), student.getCoachId(), student.getRole())))
                .andExpect(status().isForbidden());
    }
}
