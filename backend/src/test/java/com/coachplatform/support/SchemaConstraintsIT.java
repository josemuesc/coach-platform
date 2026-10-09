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

    @Autowired
    javax.sql.DataSource dataSource;

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

    // ---- V8: payment method OTHER, the reference and the immutability ----------------------------------------

    private UUID payment(String method, String reference) {
        UUID coach = coach();
        UUID student = student(coach);
        UUID cycle = activeCycle(coach, student, plan(coach));
        return jdbc.queryForObject("INSERT INTO payment (coach_id, student_id, cycle_id, amount_cop, method, paid_on, recorded_by, reference) "
                + "VALUES (?, ?, ?, 520000, ?, '2026-10-06', ?, ?) RETURNING id", UUID.class, coach, student, cycle, method, appUser(coach), reference);
    }

    @Test
    void thePaymentMethodAcceptsOtherAndNothingUnknown() {
        assertThat(payment("OTHER", null)).isNotNull();
        for (String ok : new String[] {"NEQUI", "TRANSFER", "CASH"}) {
            assertThat(payment(ok, null)).isNotNull();
        }
        assertThatThrownBy(() -> payment("BITCOIN", null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theReferenceCheckRefusesWhatTheApplicationWouldRefuse() {
        assertThat(payment("NEQUI", "M12345678")).isNotNull();
        assertThat(payment("NEQUI", "12345678901")).isNotNull();   // 11 digits
        for (String bad : new String[] {"", "   ", " padded ", "a\nb", "a\tb", "123456789012", "x 4111111111111111 y", "a".repeat(101)}) {
            assertThatThrownBy(() -> payment("NEQUI", bad)).as(bad.replaceAll("\\p{C}", "?")).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void aPaymentCanNeverBeUpdatedDeletedOrTruncated() {
        UUID payment = payment("CASH", "M1");
        assertThatThrownBy(() -> jdbc.update("UPDATE payment SET amount_cop = 1 WHERE id = ?", payment)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("UPDATE payment SET reference = NULL WHERE id = ?", payment)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM payment WHERE id = ?", payment)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE payment")).hasMessageContaining("immutable");
        assertThat(jdbc.queryForObject("SELECT amount_cop FROM payment WHERE id = ?", Long.class, payment)).isEqualTo(520000L);
    }

    /** docs/payment-correction.md, step by step: the documented transaction really corrects a payment and leaves the protection ON. */
    @Test
    void theDocumentedCorrectionProcedureWorksAndLeavesTheProtectionOn() throws Exception {
        UUID payment = payment("CASH", "M1");
        try (java.sql.Connection c = dataSource.getConnection(); java.sql.Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("SELECT id FROM payment WHERE id = '" + payment + "' FOR UPDATE");
            st.execute("ALTER TABLE payment DISABLE TRIGGER trg_payment_immutable");
            assertThat(st.executeUpdate("UPDATE payment SET amount_cop = 480000, method = 'TRANSFER', reference = 'M2' WHERE id = '" + payment + "'")).isEqualTo(1);
            st.execute("ALTER TABLE payment ENABLE TRIGGER trg_payment_immutable");
            try (var rs = st.executeQuery("SELECT tgname, tgenabled FROM pg_trigger WHERE tgrelid = 'payment'::regclass AND NOT tgisinternal ORDER BY tgname")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("tgname")).isEqualTo("trg_payment_immutable");
                assertThat(rs.getString("tgenabled")).isEqualTo("O");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("tgname")).isEqualTo("trg_payment_no_truncate");
                assertThat(rs.getString("tgenabled")).isEqualTo("O");
            }
            // the verification queries of the document run as written
            st.executeQuery("SELECT p.paid_on = c.start_date AS fecha_ok, p.student_id = c.student_id AS alumno_ok FROM payment p "
                    + "JOIN cycle c ON c.id = p.cycle_id AND c.coach_id = p.coach_id WHERE p.id = '" + payment + "'").close();
            st.executeQuery("SELECT c.classes_used, (SELECT count(*) FROM session_attendance a WHERE a.cycle_id = c.id AND a.status IN ('ATTENDED', 'NO_SHOW')) "
                    + "AS marcadas FROM cycle c WHERE c.id = (SELECT cycle_id FROM payment WHERE id = '" + payment + "')").close();
            c.commit();
        }
        assertThat(jdbc.queryForMap("SELECT amount_cop, method, reference FROM payment WHERE id = ?", payment))
                .containsEntry("amount_cop", 480000L).containsEntry("method", "TRANSFER").containsEntry("reference", "M2");
        // and it is immutable again
        assertThatThrownBy(() -> jdbc.update("UPDATE payment SET amount_cop = 1 WHERE id = ?", payment)).hasMessageContaining("immutable");
        // a rolled-back rehearsal leaves everything as it was, protection included
        try (java.sql.Connection c = dataSource.getConnection(); java.sql.Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("ALTER TABLE payment DISABLE TRIGGER trg_payment_immutable");
            st.executeUpdate("UPDATE payment SET amount_cop = 7 WHERE id = '" + payment + "'");
            c.rollback();
        }
        assertThat(jdbc.queryForObject("SELECT amount_cop FROM payment WHERE id = ?", Long.class, payment)).isEqualTo(480000L);
        assertThatThrownBy(() -> jdbc.update("UPDATE payment SET amount_cop = 1 WHERE id = ?", payment)).hasMessageContaining("immutable");
    }
}
