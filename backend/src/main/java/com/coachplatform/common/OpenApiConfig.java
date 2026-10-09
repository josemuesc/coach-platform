package com.coachplatform.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document feeds the frontend's generated, typed client. springdoc leaves every property of a Java record optional unless
 * it carries a Bean Validation constraint, which would turn every field of every response into {@code T | undefined} on the client.
 *
 * <p>Rule applied here, to response-only schemas: a property is REQUIRED unless it is declared nullable
 * ({@code @Schema(nullable = true)} on the record component). A response never omits a field: it sends null instead, so the
 * generated type says {@code T | null}. Schemas reachable from a request body keep exactly what their own validation annotations say
 * (an optional request field must stay optional). Only used when the document is served ({@code app.openapi.enabled}).
 */
@Configuration
class OpenApiConfig {

    @Bean
    OpenApiCustomizer responsePropertiesAreRequiredUnlessNullable() {
        return OpenApiConfig::apply;
    }

    static void apply(OpenAPI api) {
        if (api.getComponents() == null || api.getComponents().getSchemas() == null) {
            return;
        }
        Map<String, Schema> schemas = api.getComponents().getSchemas();
        schemas.values().forEach(OpenApiConfig::fixNullableReferences);
        Set<String> requestSide = requestSchemas(api, schemas);
        schemas.forEach((name, schema) -> {
            if (requestSide.contains(name) || schema.getProperties() == null) {
                return;
            }
            Set<String> required = new LinkedHashSet<>();
            if (schema.getRequired() != null) {
                required.addAll(schema.getRequired());
            }
            ((Map<String, Schema>) schema.getProperties()).forEach((prop, value) -> {
                if (!isNullable(value)) {
                    required.add(prop);
                }
            });
            schema.setRequired(new ArrayList<>(required));
        });
    }

    /**
     * springdoc writes a nullable object property as {@code {"$ref": X, "type": "null"}}, which reads "X and also null" and no
     * value satisfies. The meaning is "X or null": {@code oneOf [ {"$ref": X}, {"type": "null"} ]}.
     */
    private static void fixNullableReferences(Schema<?> schema) {
        if (schema.getProperties() == null) {
            return;
        }
        Map<String, Schema> props = (Map<String, Schema>) (Map) schema.getProperties();
        for (Map.Entry<String, Schema> entry : props.entrySet()) {
            Schema<?> p = entry.getValue();
            if (p.get$ref() != null && isNullable(p)) {
                Schema<?> ref = new Schema<>();
                ref.set$ref(p.get$ref());
                Schema<?> nothing = new Schema<>();
                nothing.setTypes(Set.of("null"));
                Schema<?> either = new Schema<>();
                either.setOneOf(List.of((Schema) ref, (Schema) nothing));
                entry.setValue(either);
            }
        }
    }

    /** Every schema a request body mentions, directly or through other schemas. */
    private static Set<String> requestSchemas(OpenAPI api, Map<String, Schema> schemas) {
        Deque<String> pending = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        if (api.getPaths() != null) {
            api.getPaths().values().forEach(item -> item.readOperations().forEach(op -> {
                if (op.getRequestBody() != null && op.getRequestBody().getContent() != null) {
                    op.getRequestBody().getContent().values().forEach(media -> collectRefs(media.getSchema(), pending));
                }
            }));
        }
        while (!pending.isEmpty()) {
            String name = pending.pop();
            if (seen.add(name) && schemas.get(name) != null) {
                collectRefs(schemas.get(name), pending);
            }
        }
        return seen;
    }

    private static void collectRefs(Schema<?> schema, Deque<String> out) {
        if (schema == null) {
            return;
        }
        if (schema.get$ref() != null) {
            out.push(schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1));
        }
        if (schema.getProperties() != null) {
            ((Map<String, Schema<?>>) (Map) schema.getProperties()).values().forEach(p -> collectRefs(p, out));
        }
        collectRefs(schema.getItems(), out);
        for (List<Schema> group : java.util.Arrays.asList(schema.getAllOf(), schema.getOneOf(), schema.getAnyOf())) {
            if (group != null) {
                group.forEach(s -> collectRefs(s, out));
            }
        }
        if (schema.getAdditionalProperties() instanceof Schema<?> extra) {
            collectRefs(extra, out);
        }
    }

    /** OAS 3.1 ({@code type: [x, "null"]}), OAS 3.0 ({@code nullable: true}) and {@code oneOf/anyOf} with a null branch. */
    private static boolean isNullable(Schema<?> s) {
        if (Boolean.TRUE.equals(s.getNullable())) {
            return true;
        }
        if (s.getTypes() != null && s.getTypes().contains("null")) {
            return true;
        }
        for (List<Schema> group : java.util.Arrays.asList(s.getOneOf(), s.getAnyOf())) {
            if (group != null && group.stream().anyMatch(OpenApiConfig::isNullable)) {
                return true;
            }
        }
        return "null".equals(s.getType());
    }
}
