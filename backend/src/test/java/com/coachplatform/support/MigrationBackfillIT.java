package com.coachplatform.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V4 on a database that ALREADY has plans, students and cycles (migrated only up to V3): the existing plans and cycles must
 * become PERSONALIZED, and from then on a plan cannot be created without stating its modality.
 */
@Testcontainers
class MigrationBackfillIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void existingPlansAndCyclesBecomePersonalizedAndNewPlansMustStateTheirModality() {
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("3").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        UUID coach = jdbc.queryForObject("INSERT INTO coach (name, brand_name) VALUES ('c', 'c') RETURNING id", UUID.class);
        jdbc.update("INSERT INTO coach_settings (coach_id) VALUES (?)", coach);
        UUID plan = jdbc.queryForObject("INSERT INTO plan (coach_id, name, classes_included, price_cop) VALUES (?, '8 clases', 8, 520000) RETURNING id", UUID.class, coach);
        UUID student = jdbc.queryForObject("INSERT INTO student (coach_id, full_name, email) VALUES (?, 's', 's@test.co') RETURNING id", UUID.class, coach);
        UUID cycle = jdbc.queryForObject("INSERT INTO cycle (coach_id, student_id, plan_id, start_date, end_date, original_end_date, classes_included, status) "
                + "VALUES (?, ?, ?, '2026-10-06', '2026-11-06', '2026-11-06', 8, 'ACTIVE') RETURNING id", UUID.class, coach, student, plan);

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();   // now up to the latest (V4)

        assertThat(jdbc.queryForObject("SELECT modality FROM plan WHERE id = ?", String.class, plan)).isEqualTo("PERSONALIZED");
        assertThat(jdbc.queryForObject("SELECT modality FROM cycle WHERE id = ?", String.class, cycle)).isEqualTo("PERSONALIZED");
        assertThat(jdbc.queryForObject("SELECT default_group_capacity FROM coach_settings WHERE coach_id = ?", Integer.class, coach)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT class_duration_minutes FROM coach_settings WHERE coach_id = ?", Integer.class, coach)).isEqualTo(60);

        // the DEFAULT was only for the backfill: a NEW plan or cycle must state its modality
        assertThatThrownBy(() -> jdbc.update("INSERT INTO plan (coach_id, name, classes_included, price_cop) VALUES (?, 'otro', 8, 1)", coach))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_policies WHERE schemaname = 'public'", Integer.class)).isZero();
    }
}
