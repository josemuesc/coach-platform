package com.coachplatform.auth;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.coachplatform.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Measuring milliseconds is flaky, so this checks the cause of the timing leak instead: every login - unknown email, wrong
 * password, suspended account, right password - runs exactly ONE BCrypt comparison.
 */
class LoginTimingTest extends ApiIntegrationTest {

    @MockitoSpyBean PasswordEncoder encoder;

    private void tryLogin(String email, String password) throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    @Test
    void everyLoginRunsExactlyOneBcryptComparison() throws Exception {
        String known = uniqueEmail("known");
        registerCoach(known);
        String suspended = uniqueEmail("susp");
        registerCoach(suspended);
        jdbc.update("update app_user set active = false where lower(email) = ?", suspended);

        for (String[] attempt : new String[][] {
                {uniqueEmail("ghost"), "Clave-cualquiera-1"},   // unknown email
                {known, "Clave-cualquiera-1"},                  // wrong password
                {suspended, PASSWORD},                          // suspended, right password
                {known, PASSWORD}}) {                           // success
            clearInvocations(encoder);
            tryLogin(attempt[0], attempt[1]);
            verify(encoder, times(1)).matches(anyString(), anyString());
        }
    }
}
