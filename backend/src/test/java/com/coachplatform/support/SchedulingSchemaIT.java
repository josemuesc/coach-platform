package com.coachplatform.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V4 constraints on real PostgreSQL: events (exclusion, capacity), attendances (shared events, composite FKs, overrides). */
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
        UUID student = student(coach);
        return new Setup(coach, user, student, cycle(coach, student));
    }

    private UUID student(UUID coach) {
        return jdbc.queryForObject("INSERT INTO student (coach_id, full_name, email) VALUES (?, 's', ?) RETURNING id",
                UUID.class, coach, UUID.randomUUID() + "@test.co");
    }

    private UUID cycle(UUID coach, UUID student) {
        UUID plan = jdbc.queryForObject("INSERT INTO plan (coach_id, name, classes_included, price_cop, modality) "
                + "VALUES (?, ?, 8, 1, 'SEMI_PERSONALIZED') RETURNING id", UUID.class, coach, "p-" + UUID.randomUUID());
        return jdbc.queryForObject("INSERT INTO cycle (coach_id, student_id, plan_id, start_date, end_date, original_end_date, "
                + "classes_included, status, modality) VALUES (?, ?, ?, '2026-10-06', '2026-11-06', '2026-11-06', 8, 'ACTIVE', 'SEMI_PERSONALIZED') RETURNING id",
                UUID.class, coach, student, plan);
    }

    private UUID event(Setup s, String start, String end, String modality, int capacity, String status) {
        return jdbc.queryForObject("INSERT INTO class_session (coach_id, starts_at, ends_at, modality, capacity, status, created_by, cancelled_at) "
                + "VALUES (?, ?::timestamptz, ?::timestamptz, ?, ?, ?, ?, CASE WHEN ? = 'CANCELLED' THEN now() END) RETURNING id",
                UUID.class, s.coach(), start, end, modality, capacity, status, s.user(), status);
    }

    private int attendance(Setup s, UUID event, UUID student, UUID cycle, String status) {
        return jdbc.update("INSERT INTO session_attendance (coach_id, session_id, student_id, cycle_id, status, created_by, cancelled_at, "
                + "cancel_reason, marked_at) VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? IN ('CANCELLED_ON_TIME','RESCHEDULED','CANCELLED_BY_COACH') THEN now() END, "
                + "CASE WHEN ? = 'CANCELLED_BY_COACH' THEN 'motivo' END, CASE WHEN ? IN ('ATTENDED','NO_SHOW') THEN now() END)",
                s.coach(), event, student, cycle, status, s.user(), status, status, status);
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

    // ================================================================= events never overlap

    @Test
    void twoScheduledEventsOfTheSameCoachCannotOverlap() {
        Setup s = setup();
        event(s, "2026-10-12 15:00+00", "2026-10-12 16:00+00", "SEMI_PERSONALIZED", 4, "SCHEDULED");

        for (String[] range : new String[][] {
                {"2026-10-12 15:00+00", "2026-10-12 16:00+00"},
                {"2026-10-12 15:30+00", "2026-10-12 16:30+00"},
                {"2026-10-12 14:00+00", "2026-10-12 17:00+00"}}) {
            assertThatThrownBy(() -> event(s, range[0], range[1], "PERSONALIZED", 1, "SCHEDULED"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(e -> assertThat(sqlState(e)).as("exclusion_violation").isEqualTo("23P01"));
        }
    }

    @Test
    void backToBackEventsOtherCoachesAndCancelledEventsAreFine() {
        Setup s = setup();
        event(s, "2026-10-12 15:00+00", "2026-10-12 16:00+00", "SEMI_PERSONALIZED", 4, "SCHEDULED");
        event(s, "2026-10-12 16:00+00", "2026-10-12 17:00+00", "PERSONALIZED", 1, "SCHEDULED");           // back to back

        Setup other = setup();                                                                               // another coach, same time
        event(other, "2026-10-12 15:00+00", "2026-10-12 16:00+00", "SEMI_PERSONALIZED", 4, "SCHEDULED");

        UUID cancelled = event(s, "2026-10-13 15:00+00", "2026-10-13 16:00+00", "SEMI_PERSONALIZED", 4, "CANCELLED");
        event(s, "2026-10-13 15:00+00", "2026-10-13 16:00+00", "PERSONALIZED", 1, "SCHEDULED");           // a cancelled event frees its time
        assertThat(cancelled).isNotNull();
    }

    @Test
    void anEventsCapacityMatchesItsModality() {
        Setup s = setup();
        event(s, "2026-10-12 08:00+00", "2026-10-12 09:00+00", "PERSONALIZED", 1, "SCHEDULED");
        event(s, "2026-10-12 09:00+00", "2026-10-12 10:00+00", "SEMI_PERSONALIZED", 2, "SCHEDULED");
        event(s, "2026-10-12 10:00+00", "2026-10-12 11:00+00", "SEMI_PERSONALIZED", 10, "SCHEDULED");

        assertThatThrownBy(() -> event(s, "2026-10-13 08:00+00", "2026-10-13 09:00+00", "PERSONALIZED", 2, "SCHEDULED")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> event(s, "2026-10-13 08:00+00", "2026-10-13 09:00+00", "SEMI_PERSONALIZED", 1, "SCHEDULED")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> event(s, "2026-10-13 08:00+00", "2026-10-13 09:00+00", "SEMI_PERSONALIZED", 11, "SCHEDULED")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> event(s, "2026-10-13 08:00+00", "2026-10-13 09:00+00", "GRUPAL", 4, "SCHEDULED")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> event(s, "2026-10-13 09:00+00", "2026-10-13 08:00+00", "PERSONALIZED", 1, "SCHEDULED")).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ================================================================= attendances

    @Test
    void severalStudentsShareOneEventButNobodyHoldsTwoLivePlacesInIt() {
        Setup s = setup();
        UUID second = student(s.coach());
        UUID secondCycle = cycle(s.coach(), second);
        UUID event = event(s, "2026-10-12 15:00+00", "2026-10-12 16:00+00", "SEMI_PERSONALIZED", 4, "SCHEDULED");

        assertThat(attendance(s, event, s.student(), s.cycle(), "SCHEDULED")).isEqualTo(1);
        assertThat(attendance(s, event, second, secondCycle, "SCHEDULED")).as("two students share the event").isEqualTo(1);
        assertThatThrownBy(() -> attendance(s, event, s.student(), s.cycle(), "SCHEDULED"))
                .isInstanceOf(DataIntegrityViolationException.class).satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"));
        // cancelled / rescheduled places do not count: the student can come back
        jdbc.update("UPDATE session_attendance SET status = 'CANCELLED_ON_TIME', cancelled_at = now() WHERE session_id = ? AND student_id = ?", event, s.student());
        assertThat(attendance(s, event, s.student(), s.cycle(), "SCHEDULED")).isEqualTo(1);
    }

    @Test
    void anAttendanceCannotPointToAnEventStudentOrCycleOfAnotherCoach() {
        Setup a = setup();
        Setup b = setup();
        UUID eventA = event(a, "2026-10-12 15:00+00", "2026-10-12 16:00+00", "SEMI_PERSONALIZED", 4, "SCHEDULED");

        assertThatThrownBy(() -> attendance(b, eventA, b.student(), b.cycle(), "SCHEDULED")).isInstanceOf(DataIntegrityViolationException.class);   // event of A
        assertThatThrownBy(() -> attendance(a, eventA, b.student(), a.cycle(), "SCHEDULED")).isInstanceOf(DataIntegrityViolationException.class);   // student of B
        assertThatThrownBy(() -> attendance(a, eventA, a.student(), b.cycle(), "SCHEDULED")).isInstanceOf(DataIntegrityViolationException.class);   // cycle of B
        assertThat(attendance(a, eventA, a.student(), a.cycle(), "SCHEDULED")).isEqualTo(1);
    }

    @Test
    void anOverrideNeedsAReasonAndAnAuthor() {
        Setup s = setup();
        UUID event = event(s, "2026-10-12 15:00+00", "2026-10-12 16:00+00", "SEMI_PERSONALIZED", 4, "SCHEDULED");
        String insert = "INSERT INTO session_attendance (coach_id, session_id, student_id, cycle_id, status, created_by, override, override_reason, override_by) "
                + "VALUES (?, ?, ?, ?, 'SCHEDULED', ?, true, ?, ?)";

        assertThatThrownBy(() -> jdbc.update(insert, s.coach(), event, s.student(), s.cycle(), s.user(), null, s.user())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, s.coach(), event, s.student(), s.cycle(), s.user(), "  ", s.user())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, s.coach(), event, s.student(), s.cycle(), s.user(), "motivo", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(insert, s.coach(), event, s.student(), s.cycle(), s.user(), "motivo", s.user())).isEqualTo(1);
    }

    @Test
    void checkConstraintsKeepAttendancesConsistent() {
        Setup s = setup();
        UUID event = event(s, "2026-10-12 15:00+00", "2026-10-12 16:00+00", "SEMI_PERSONALIZED", 4, "SCHEDULED");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO session_attendance (coach_id, session_id, student_id, cycle_id, status, created_by, cancelled_at) "
                + "VALUES (?, ?, ?, ?, 'CANCELLED_BY_COACH', ?, now())", s.coach(), event, s.student(), s.cycle(), s.user()))
                .isInstanceOf(DataIntegrityViolationException.class);                              // a coach cancellation needs a reason
        assertThatThrownBy(() -> jdbc.update("INSERT INTO session_attendance (coach_id, session_id, student_id, cycle_id, status, created_by) "
                + "VALUES (?, ?, ?, ?, 'ATTENDED', ?)", s.coach(), event, s.student(), s.cycle(), s.user()))
                .isInstanceOf(DataIntegrityViolationException.class);                              // attended without a mark time
        assertThatThrownBy(() -> jdbc.update("INSERT INTO session_attendance (coach_id, session_id, student_id, cycle_id, status, created_by) "
                + "VALUES (?, ?, ?, ?, 'BOGUS', ?)", s.coach(), event, s.student(), s.cycle(), s.user()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ================================================================= coach settings and plan modality

    @Test
    void settingsRangesAreEnforced() {
        UUID coach = coach();
        jdbc.update("INSERT INTO coach_settings (coach_id) VALUES (?)", coach);
        assertThat(jdbc.queryForObject("SELECT class_duration_minutes FROM coach_settings WHERE coach_id = ?", Integer.class, coach)).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT default_group_capacity FROM coach_settings WHERE coach_id = ?", Integer.class, coach)).isEqualTo(4);

        for (String bad : new String[] {"class_duration_minutes = 10", "class_duration_minutes = 181", "cancel_window_hours = 49",
                "default_group_capacity = 1", "default_group_capacity = 11"}) {
            assertThatThrownBy(() -> jdbc.update("UPDATE coach_settings SET " + bad + " WHERE coach_id = ?", coach))
                    .as(bad).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(jdbc.update("UPDATE coach_settings SET default_group_capacity = 2 WHERE coach_id = ?", coach)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE coach_settings SET default_group_capacity = 10 WHERE coach_id = ?", coach)).isEqualTo(1);
    }

    @Test
    void aNewPlanMustStateItsModality() {
        UUID coach = coach();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO plan (coach_id, name, classes_included, price_cop) VALUES (?, 'sin modalidad', 8, 1)", coach))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO plan (coach_id, name, classes_included, price_cop, modality) VALUES (?, 'mala', 8, 1, 'GRUPAL')", coach))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update("INSERT INTO plan (coach_id, name, classes_included, price_cop, modality) VALUES (?, 'ok', 8, 1, 'PERSONALIZED')", coach)).isEqualTo(1);
    }
}
