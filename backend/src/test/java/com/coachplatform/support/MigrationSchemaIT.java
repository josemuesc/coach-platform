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

    @Test
    void everyTableHasRowLevelSecurityEnabled() {
        List<String> withoutRls = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                        + "AND tablename <> 'flyway_schema_history' AND NOT rowsecurity", String.class);
        assertThat(withoutRls).isEmpty();
    }

    @Test
    void emailIsCaseInsensitivelyUnique() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_app_user_email'", Integer.class);
        assertThat(n).isEqualTo(1);
    }
}
