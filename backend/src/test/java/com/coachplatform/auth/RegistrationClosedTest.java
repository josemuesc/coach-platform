package com.coachplatform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.coachplatform.support.ApiIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/** Coach self-registration is closed unless explicitly opened, and a closed door answers the same for any e-mail. */
@TestPropertySource(properties = "app.registration.open=false")
class RegistrationClosedTest extends ApiIntegrationTest {

    private MvcResult register(String email) throws Exception {
        return mvc.perform(post("/api/auth/register-coach").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"C\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
    }

    @Test
    void registrationIsRefusedAndTheAnswerDoesNotDependOnTheEmail() throws Exception {
        String taken = uniqueEmail("taken");
        auth.registerCoach(new AuthDtos.RegisterCoachRequest("Existing", taken, PASSWORD));

        MvcResult fresh = register(uniqueEmail("fresh"));
        MvcResult existing = register(taken);

        assertThat(fresh.getResponse().getStatus()).isEqualTo(403);
        assertThat(existing.getResponse().getStatus()).isEqualTo(403);
        assertThat(json(existing)).isEqualTo(json(fresh)).contains("REGISTRATION_CLOSED");
        assertThat(jdbc.queryForObject("select count(*) from app_user where email like 'fresh-%'", Integer.class)).isZero();   // nothing created
    }

    @org.springframework.beans.factory.annotation.Autowired AuthService auth;

    @Test
    void aClosedDoorIsStillThrottledPerAddress() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(fromIp(post("/api/auth/register-coach"), "10.21.0.1").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"C\",\"email\":\"" + uniqueEmail("closed") + "\",\"password\":\"" + PASSWORD + "\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        }
        mvc.perform(fromIp(post("/api/auth/register-coach"), "10.21.0.1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"C\",\"email\":\"" + uniqueEmail("closed") + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isTooManyRequests());
    }

    @Test
    void theShippedDefaultIsClosed() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yml).contains("open: ${APP_REGISTRATION_OPEN:false}");
    }
}
