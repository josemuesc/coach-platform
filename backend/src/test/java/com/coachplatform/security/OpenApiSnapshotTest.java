package com.coachplatform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.coachplatform.support.ApiIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * frontend/openapi/openapi.json is the contract the typed client is generated from. This test fails when the API changed and the
 * snapshot did not: regenerate it with {@code mvn test -Dtest=OpenApiSnapshotTest -Dopenapi.update=true} and commit the result
 * together with the regenerated client. It also pins the rule that responses carry {@code required} fields.
 */
@TestPropertySource(properties = "app.openapi.enabled=true")
class OpenApiSnapshotTest extends ApiIntegrationTest {

    private static final Path SNAPSHOT = Path.of("..", "frontend", "openapi", "openapi.json");

    private String currentDocument() throws Exception {
        String raw = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        JsonMapper mapper = JsonMapper.builder().build();
        // stable text: keys sorted, 2-space indent, trailing newline; the server URL of the test is not part of the contract
        var tree = mapper.readTree(raw);
        ((tools.jackson.databind.node.ObjectNode) tree).remove("servers");
        Object sorted = mapper.treeToValue(tree, Object.class);
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sortKeys(sorted)) + "\n";
    }

    @SuppressWarnings("unchecked")
    private static Object sortKeys(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), sortKeys(v)));
            return sorted;
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(OpenApiSnapshotTest::sortKeys).toList();
        }
        return value;
    }

    @Test
    void theCommittedSnapshotIsTheCurrentApi() throws Exception {
        String current = currentDocument();
        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, current);
            return;
        }
        assertThat(Files.exists(SNAPSHOT)).as("run the update command in this class's javadoc").isTrue();
        assertThat(Files.readString(SNAPSHOT)).as("frontend/openapi/openapi.json is out of date: regenerate it").isEqualTo(current);
    }

    @Test
    void responseFieldsAreRequiredAndNullableOnesSayNull() throws Exception {
        var schemas = new ObjectMapper().readTree(currentDocument()).path("components").path("schemas");
        // a response: every field required, the nullable ones typed with "null"
        var me = schemas.path("MeResponse");
        assertThat(me.path("required").toString()).contains("userId", "role", "brandName").doesNotContain("primaryColor", "passwordChangedAt");
        assertThat(me.path("properties").path("primaryColor").path("type").toString()).contains("null");
        // a request keeps its own validation: an optional field stays optional
        var pay = schemas.path("RegisterPaymentCommand");
        assertThat(pay.path("required").toString()).contains("planId").doesNotContain("paidOn");
        // sheet cycle and history activeCycle can be null
        var history = schemas.path("HistoryView");
        assertThat(history.path("required").toString()).contains("cycles").doesNotContain("activeCycle");
        // a nullable object is "X or null" (oneOf), never "$ref X with type null", which nothing can satisfy
        var active = history.path("properties").path("activeCycle");
        assertThat(active.has("$ref")).isFalse();
        assertThat(active.path("oneOf").toString()).contains("#/components/schemas/CycleSummary").contains("null");
    }
}
