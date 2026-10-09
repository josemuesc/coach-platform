package com.coachplatform.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coachplatform.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** register-coach is throttled per IP in BOTH states: open (failed attempts count) and closed (every refusal counts). */
class RegistrationRateLimitTest extends ApiIntegrationTest {

    private org.springframework.test.web.servlet.ResultActions register(String ip, String email) throws Exception {
        return mvc.perform(fromIp(post("/api/auth/register-coach"), ip).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"C\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"));
    }

    @Test
    void repeatedFailedRegistrationsFromOneAddressAreCutOffButSuccessfulOnesDoNotCount() throws Exception {
        String taken = uniqueEmail("taken");
        register("10.20.0.1", taken).andExpect(status().isCreated());
        for (int i = 0; i < 10; i++) {
            register("10.20.0.1", taken).andExpect(status().isConflict());
        }
        register("10.20.0.1", uniqueEmail("late")).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
        register("10.20.0.2", uniqueEmail("other")).andExpect(status().isCreated());   // another address is unaffected

        for (int i = 0; i < 12; i++) {
            register("10.20.0.3", uniqueEmail("ok" + i)).andExpect(status().isCreated());   // successes never count
        }
    }
}
