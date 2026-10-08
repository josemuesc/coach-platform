package com.coachplatform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coachplatform.support.ApiIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.context.ApplicationContext;

class AuthHardeningTest extends ApiIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired UserDetailsService userDetailsService;

    private void attemptLogin(String email, String password, String ip, int expectedStatus) throws Exception {
        mvc.perform(fromIp(post("/api/auth/login"), ip).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void thereIsNoGeneratedInMemoryUser() {
        assertThat(context.getBeansOfType(InMemoryUserDetailsManager.class)).isEmpty();
        org.junit.jupiter.api.Assertions.assertThrows(UsernameNotFoundException.class,
                () -> userDetailsService.loadUserByUsername("user"));
    }

    @Test
    void passwordMustHaveAtLeastTenCharacters() throws Exception {
        mvc.perform(post("/api/auth/register-coach").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"C\",\"email\":\"" + uniqueEmail("pw9") + "\",\"password\":\"123456789\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register-coach").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"C\",\"email\":\"" + uniqueEmail("pw10") + "\",\"password\":\"1234567890\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void fiveFailedLoginsForAnEmailBlockEvenTheCorrectPasswordUntilTheWindowPasses() throws Exception {
        String email = uniqueEmail("brute");
        registerCoach(email);

        for (int i = 0; i < 5; i++) {
            attemptLogin(email, "wrong-password-" + i, "10.1.0." + i, 401);
        }
        attemptLogin(email, PASSWORD, "10.1.0.99", 429);

        clock.advance(Duration.ofMinutes(16));
        attemptLogin(email, PASSWORD, "10.1.0.99", 200);
    }

    @Test
    void unknownEmailsAreThrottledTooSoExistenceIsNotRevealed() throws Exception {
        String email = uniqueEmail("ghost");
        for (int i = 0; i < 5; i++) {
            attemptLogin(email, "whatever-pass", "10.2.0." + i, 401);
        }
        attemptLogin(email, "whatever-pass", "10.2.0.99", 429);
    }

    @Test
    void aSuccessfulLoginResetsTheEmailCounter() throws Exception {
        String email = uniqueEmail("reset");
        registerCoach(email);

        for (int i = 0; i < 4; i++) {
            attemptLogin(email, "wrong-password", "10.3.0." + i, 401);
        }
        attemptLogin(email, PASSWORD, "10.3.0.50", 200);
        for (int i = 0; i < 4; i++) {
            attemptLogin(email, "wrong-password", "10.3.0." + (60 + i), 401);
        }
        attemptLogin(email, PASSWORD, "10.3.0.99", 200);
    }

    @Test
    void manyFailedLoginsFromOneIpAreBlockedByTheFilter() throws Exception {
        String ip = "10.4.0.1";
        for (int i = 0; i < 30; i++) {
            attemptLogin(uniqueEmail("ip"), "wrong-password", ip, 401);
        }
        mvc.perform(fromIp(post("/api/auth/login"), ip).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + uniqueEmail("ip") + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));

        // another IP is unaffected
        attemptLogin(uniqueEmail("other"), "wrong-password", "10.4.0.2", 401);
    }

    @Test
    void secretsAreRedactedInToString() {
        assertThat(new AuthDtos.LoginRequest("a@b.co", "super-secret-pw").toString()).doesNotContain("super-secret-pw");
        assertThat(new AuthDtos.RegisterCoachRequest("n", "a@b.co", "super-secret-pw").toString()).doesNotContain("super-secret-pw");
        assertThat(new AuthDtos.ChangePasswordRequest("old-secret-pw", "new-secret-pw").toString())
                .doesNotContain("old-secret-pw").doesNotContain("new-secret-pw");
    }
}
