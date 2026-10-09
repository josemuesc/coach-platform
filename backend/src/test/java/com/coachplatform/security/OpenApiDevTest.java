package com.coachplatform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coachplatform.support.ApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** With app.openapi.enabled=true (what the dev profile sets) the contract document is served without a token. */
@TestPropertySource(properties = "app.openapi.enabled=true")
class OpenApiDevTest extends ApiIntegrationTest {

    @Test
    void theOpenApiDocumentListsTheApi() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/student/sessions")));
    }
}
