package com.coachplatform.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The database rejects bad data on its own, even if a service had a bug (raw SQL, no application code involved). */
class SchemaConstraintsIT extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private UUID coach() {
        return jdbc.queryForObject("INSERT INTO coach (name, brand_name) VALUES ('c', 'c') RETURNING id", UUID.class);
    }

    private UUID plan(UUID coach) {
        return jdbc.queryForObject("INSERT INTO plan (coach_id, name, classes_included, price_cop, modality) "
                + "VALUES (?, ?, 8, 520000, 'PERSONALIZED') RETURNING id", UUID.class, coach, "plan-" + UUID.randomUUID());
    }

    private UUID student(UUID coach) {
        return jdbc.queryForObject("INSERT INTO student (coach_id, full_name, email) VALUES (?, 's', ?) RETURNING id",
                UUID.class, coach, UUID.randomUUID() + "@test.co");
    }

    private UUID appUser(UUID coach) {
        return jdbc.queryForObject("INSERT INTO app_user (coach_id, email, password_hash, role) "
                + "VALUES (?, ?, 'h', 'COACH') RETURNING id", UUID.class, coach, UUID.randomUUID() + "@test.co");
    }

    private UUID activeCycle(UUID coach, UUID student, UUID plan) {
        return jdbc.queryForObject("INSERT INTO cycle (coach_id, student_id, plan_id, start_date, end_date, original_end_date, "
                + "classes_included, status, modality) VALUES (?, ?, ?, '2026-10-06', '2026-11-06', '2026-11-06', 8, 'ACTIVE', 'PERSONALIZED') RETURNING id",
                UUID.class, coach, student, plan);
    }

    private int closedCycle(UUID coach, UUID student, UUID plan) {
        return jdbc.update("INSERT INTO cycle (coach_id, student_id, plan_id, start_date, end_date, original_end_date, "
                + "classes_included, status, closed_at, modality) VALUES (?, ?, ?, '2026-08-01', '2026-09-01', '2026-09-01', 8, 'EXPIRED', now(), 'PERSONALIZED')",
                coach, student, plan);
    }

    @Test
    void aSecondActiveCycleForTheSameStudentIsRejectedByThePartialUniqueIndex() {
        UUID coach = coach();
        UUID student = student(coach);
        UUID plan = plan(coach);
        activeCycle(coach, student, plan);

        assertThatThrownBy(() -> activeCycle(coach, student, plan)).isInstanceOf(DuplicateKeyException.class);
        // closed cycles are not limited
        assertThat(closedCycle(coach, student, plan)).isEqualTo(1);
        assertThat(closedCycle(coach, student, plan)).isEqualTo(1);
    }

    @Test
    void twoStudentsCanEachHaveTheirOwnActiveCycle() {
        UUID coach = coach();
        UUID plan = plan(coach);
        activeCycle(coach, student(coach), plan);
        activeCycle(coach, student(coach), plan);
    }

    @Test
    void aCycleCannotPointToAStudentOrPlanOfAnotherCoach() {
        UUID coachA = coach();
        UUID coachB = coach();
        UUID studentA = student(coachA);
        UUID planA = plan(coachA);
        UUID studentB = student(coachB);
        UUID planB = plan(coachB);

        assertThatThrownBy(() -> closedCycle(coachB, studentA, planB)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> closedCycle(coachB, studentB, planA)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(closedCycle(coachB, studentB, planB)).isEqualTo(1);
    }

    @Test
    void aPaymentCannotPointToACycleOrStudentOfAnotherCoach() {
        UUID coachA = coach();
        UUID coachB = coach();
        UUID studentA = student(coachA);
        UUID studentB = student(coachB);
        UUID cycleA = activeCycle(coachA, studentA, plan(coachA));
        UUID user = appUser(coachB);
        String insert = "INSERT INTO payment (coach_id, student_id, cycle_id, amount_cop, method, paid_on, recorded_by) "
                + "VALUES (?, ?, ?, 520000, 'NEQUI', '2026-10-06', ?)";

        assertThatThrownBy(() -> jdbc.update(insert, coachB, studentB, cycleA, user)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, coachB, studentA, cycleA, user)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(insert, coachA, studentA, cycleA, appUser(coachA))).isEqualTo(1);
    }

    @Test
    void aCycleHasAtMostOnePayment() {
        UUID coach = coach();
        UUID student = student(coach);
        UUID cycle = activeCycle(coach, student, plan(coach));
        UUID user = appUser(coach);
        String insert = "INSERT INTO payment (coach_id, student_id, cycle_id, amount_cop, method, paid_on, recorded_by) "
                + "VALUES (?, ?, ?, 520000, 'CASH', '2026-10-06', ?)";
        jdbc.update(insert, coach, student, cycle, user);

        assertThatThrownBy(() -> jdbc.update(insert, coach, student, cycle, user)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void anExtensionCannotPointToACycleOfAnotherCoach() {
        UUID coachA = coach();
        UUID coachB = coach();
        UUID cycleA = activeCycle(coachA, student(coachA), plan(coachA));
        UUID userB = appUser(coachB);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO cycle_extension (coach_id, cycle_id, previous_end_date, new_end_date, "
                + "reason, extended_by) VALUES (?, ?, '2026-11-06', '2026-11-13', 'x', ?)", coachB, cycleA, userB))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checkConstraintsRejectInconsistentCycles() {
        UUID coach = coach();
        UUID student = student(coach);
        UUID plan = plan(coach);
        UUID cycle = activeCycle(coach, student, plan);

        assertThatThrownBy(() -> jdbc.update("UPDATE cycle SET classes_used = 9 WHERE id = ?", cycle))
                .isInstanceOf(DataIntegrityViolationException.class);                      // more used than included
        assertThatThrownBy(() -> jdbc.update("UPDATE cycle SET status = 'EXPIRED' WHERE id = ?", cycle))
                .isInstanceOf(DataIntegrityViolationException.class);                      // closed without closed_at
        assertThatThrownBy(() -> jdbc.update("UPDATE cycle SET end_date = '2026-10-01' WHERE id = ?", cycle))
                .isInstanceOf(DataIntegrityViolationException.class);                      // deadline before the start
        assertThatThrownBy(() -> jdbc.update("INSERT INTO student (coach_id, full_name, email) VALUES (?, 's', 'MAYUS@test.co')", coach))
                .isInstanceOf(DataIntegrityViolationException.class);                      // email must be lowercase
    }
}
