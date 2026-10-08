package com.coachplatform.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V4 constraints on real PostgreSQL: the no-overlap exclusion constraint (btree_gist), composite FKs and checks. */
class SchedulingSchemaIT extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private UUID coach() {
        return jdbc.queryForObject("INSERT INTO coach (name, brand_name) VALUES ('c', 'c') RETURNING id", UUID.class);
    }

    private UUID appUser(UUID coach) {
        return jdbc.queryForObject("INSERT INTO app_user (coach_id, email, password_hash, role) "
                + "VALUES (?, ?, 'h', 'COACH') RETURNING id", UUID.class, coach, UUID.randomUUID() + "@test.co");
    }

    private record Setup(UUID coach, UUID user, UUID student, UUID cycle) {
    }

    private Setup setup() {
        UUID coach = coach();
        UUID user = appUser(coach);
        UUID plan = jdbc.queryForObject("INSERT INTO plan (coach_id, name, classes_included, price_cop) VALUES (?, ?, 8, 1) RETURNING id",
                UUID.class, coach, "p-" + UUID.randomUUID());
        UUID student = jdbc.queryForObject("INSERT INTO student (coach_id, full_name, email) VALUES (?, 's', ?) RETURNING id",
                UUID.class, coach, UUID.randomUUID() + "@test.co");
        UUID cycle = jdbc.queryForObject("INSERT INTO cycle (coach_id, student_id, plan_id, start_date, end_date, original_end_date, "
                + "classes_included, status) VALUES (?, ?, ?, '2026-10-06', '2026-11-06', '2026-11-06', 8, 'ACTIVE') RETURNING id",
                UUID.class, coach, student, plan);
        return new Setup(coach, user, student, cycle);
    }

    private UUID student(UUID coach) {
        return jdbc.queryForObject("INSERT INTO student (coach_id, full_name, email) VALUES (?, 's', ?) RETURNING id",
                UUID.class, coach, UUID.randomUUID() + "@test.co");
    }

    private int session(Setup s, UUID student, String start, String end, String status) {
        return jdbc.update("INSERT INTO class_session (coach_id, student_id, cycle_id, starts_at, ends_at, status, created_by, "
                + "cancelled_at, cancel_reason) VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz, ?, ?, "
                + "CASE WHEN ? = 'SCHEDULED' THEN NULL ELSE now() END, CASE WHEN ? = 'CANCELLED_BY_COACH' THEN 'motivo' END)",
                s.coach(), student, s.cycle(), start, end, status, s.user(), status, status);
    }

    private static String sqlState(Throwable t) {
        for (; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    @Test
    void theExtensionIsInstalled() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist'", Integer.class)).isEqualTo(1);
    }

    @Test
    void twoScheduledClassesOfTheSameCoachCannotOverlap() {
        Setup s = setup();
        session(s, s.student(), "2026-10-12 15:00+00", "2026-10-12 16:00+00", "SCHEDULED");

        // same slot, partial overlap, and a longer class covering it: all rejected by the database itself
        for (String[] range : new String[][] {
                {"2026-10-12 15:00+00", "2026-10-12 16:00+00"},
                {"2026-10-12 15:30+00", "2026-10-12 16:30+00"},
                {"2026-10-12 14:00+00", "2026-10-12 17:00+00"}}) {
            assertThatThrownBy(() -> session(s, student(s.coach()), range[0], range[1], "SCHEDULED"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(e -> assertThat(sqlState(e)).as("exclusion_violation").isEqualTo("23P01"));
        }
    }

    @Test
    void backToBackClassesAndOtherCoachesAreFine() {
        Setup s = setup();
        session(s, s.student(), "2026-10-12 15:00+00", "2026-10-12 16:00+00", "SCHEDULED");
        assertThat(session(s, student(s.coach()), "2026-10-12 16:00+00", "2026-10-12 17:00+00", "SCHEDULED")).isEqualTo(1);

        Setup other = setup();   // another coach at the very same time
        assertThat(session(other, other.student(), "2026-10-12 15:00+00", "2026-10-12 16:00+00", "SCHEDULED")).isEqualTo(1);
    }

    @Test
    void aCancelledOrMarkedClassNoLongerBlocksTheSlot() {
        Setup s = setup();
        jdbc.update("INSERT INTO class_session (coach_id, student_id, cycle_id, starts_at, ends_at, status, created_by, marked_at) "
                + "VALUES (?, ?, ?, '2026-10-12 15:00+00', '2026-10-12 16:00+00', 'ATTENDED', ?, now())", s.coach(), s.student(), s.cycle(), s.user());
        assertThat(session(s, s.student(), "2026-10-12 15:00+00", "2026-10-12 16:00+00", "CANCELLED_BY_COACH")).isEqualTo(1);
        assertThat(session(s, s.student(), "2026-10-12 15:00+00", "2026-10-12 16:00+00", "SCHEDULED")).isEqualTo(1);
    }

    @Test
    void aSessionCannotPointToAStudentOrCycleOfAnotherCoach() {
        Setup a = setup();
        Setup b = setup();
        assertThatThrownBy(() -> session(new Setup(b.coach(), b.user(), a.student(), b.cycle()), a.student(),
                "2026-10-12 15:00+00", "2026-10-12 16:00+00", "SCHEDULED")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> session(new Setup(b.coach(), b.user(), b.student(), a.cycle()), b.student(),
                "2026-10-12 15:00+00", "2026-10-12 16:00+00", "SCHEDULED")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checkConstraintsKeepSessionsConsistent() {
        Setup s = setup();
        // coach cancellation needs a reason
        assertThatThrownBy(() -> jdbc.update("INSERT INTO class_session (coach_id, student_id, cycle_id, starts_at, ends_at, status, created_by, cancelled_at) "
                + "VALUES (?, ?, ?, '2026-10-12 15:00+00', '2026-10-12 16:00+00', 'CANCELLED_BY_COACH', ?, now())",
                s.coach(), s.student(), s.cycle(), s.user())).isInstanceOf(DataIntegrityViolationException.class);
        // attended without a mark time, scheduled with a cancellation time, end before start
        assertThatThrownBy(() -> session(s, s.student(), "2026-10-12 15:00+00", "2026-10-12 16:00+00", "ATTENDED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> session(s, s.student(), "2026-10-12 16:00+00", "2026-10-12 15:00+00", "SCHEDULED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> session(s, s.student(), "2026-10-12 15:00+00", "2026-10-12 16:00+00", "BOGUS"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void settingsAndAvailabilityRangesAreEnforced() {
        UUID coach = coach();
        jdbc.update("INSERT INTO coach_settings (coach_id) VALUES (?)", coach);
        assertThat(jdbc.queryForObject("SELECT class_duration_minutes FROM coach_settings WHERE coach_id = ?", Integer.class, coach)).isEqualTo(60);
        assertThatThrownBy(() -> jdbc.update("UPDATE coach_settings SET class_duration_minutes = 10 WHERE coach_id = ?", coach)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE coach_settings SET class_duration_minutes = 181 WHERE coach_id = ?", coach)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE coach_settings SET cancel_window_hours = 49 WHERE coach_id = ?", coach)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO availability_rule (coach_id, day_of_week, start_time, end_time) VALUES (?, 8, '06:00', '07:00')", coach))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO availability_rule (coach_id, day_of_week, start_time, end_time) VALUES (?, 1, '07:00', '06:00')", coach))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
