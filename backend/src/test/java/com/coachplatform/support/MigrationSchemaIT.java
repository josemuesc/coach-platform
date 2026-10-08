package com.coachplatform.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Boots the app against real Postgres: Flyway applies all migrations and Hibernate validates the mapping. */
class MigrationSchemaIT extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    /**
     * Fails if ANY table of the public schema is left without Row Level Security, except flyway_schema_history
     * (Flyway creates it, so it is secured by hand once per database).
     */
    @Test
    void everyPublicTableExceptFlywayHistoryHasRowLevelSecurity() {
        List<String> tables = jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                + "AND tablename <> 'flyway_schema_history'", String.class);
        // not vacuous: the whole schema must be there
        assertThat(tables).contains("organization", "coach", "coach_settings", "app_user",
                "plan", "student", "invitation", "cycle", "payment", "cycle_extension");

        List<String> withoutRls = jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                + "AND tablename <> 'flyway_schema_history' AND NOT rowsecurity", String.class);
        assertThat(withoutRls).as("tables without RLS").isEmpty();
    }

    @Test
    void noRowLevelSecurityPolicyExists() {
        // RLS on and no policies = nothing is reachable through Supabase's public API keys.
        Integer policies = jdbc.queryForObject("SELECT count(*) FROM pg_policies WHERE schemaname = 'public'", Integer.class);
        assertThat(policies).isZero();
    }

    @Test
    void emailIsCaseInsensitivelyUnique() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_app_user_email'", Integer.class);
        assertThat(n).isEqualTo(1);
    }

    @Test
    void onlyOneActiveCyclePerStudentIsEnforcedByAPartialUniqueIndex() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_cycle_one_active_per_student'", String.class);
        assertThat(definition).containsIgnoringCase("UNIQUE").contains("student_id").contains("ACTIVE");
    }
}
