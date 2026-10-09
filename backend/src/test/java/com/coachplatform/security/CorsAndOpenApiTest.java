package com.coachplatform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coachplatform.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** CORS lets in only APP_FRONTEND_URL, and by default (any profile but dev) no OpenAPI document or Swagger UI exists. */
@TestPropertySource(properties = "app.frontend-url=https://app.example.co/some/path")
class CorsAndOpenApiTest extends ApiIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired org.springframework.core.env.Environment env;

    private static final String FRONTEND = "https://app.example.co";

    @Test
    void preflightFromTheFrontendOriginIsAllowedWithoutCredentials() throws Exception {
        mvc.perform(options("/api/student/sessions").header("Origin", FRONTEND)
                        .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void preflightFromAnotherOriginIsRejected() throws Exception {
        mvc.perform(options("/api/student/sessions").header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void anActualRequestFromAnotherOriginGetsNoCorsHeader() throws Exception {
        mvc.perform(get("/api/student/sessions").header("Origin", "https://evil.example.com"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void openApiAndSwaggerUiAnswer404ByDefault() throws Exception {
        for (String path : new String[] {"/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void springdocIsSwitchedOffByPropertyOutsideDev() {
        assertThat(env.getProperty("app.openapi.enabled", Boolean.class)).isFalse();
        assertThat(env.getProperty("springdoc.api-docs.enabled", Boolean.class)).isFalse();
        assertThat(env.getProperty("springdoc.swagger-ui.enabled", Boolean.class)).isFalse();
    }
}
