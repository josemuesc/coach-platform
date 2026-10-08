package com.coachplatform.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V5 on real PostgreSQL: the audit shape (ck_audit_shape), the permanence of the student confirmation (and ONLY of it),
 * consent records and revocations (signer rules, no uniqueness per version, immutability including TRUNCATE) and RLS.
 */
class ProfileConsentSchemaIT extends PostgresIntegrationTest {

    private static final String HASH = "a".repeat(64);
    private static final String CHECK = "23514";
    private static final String FK = "23503";
    private static final String IMMUTABLE = "23000";

    @Autowired
    JdbcTemplate jdbc;

    // ---- fixtures ------------------------------------------------------------------------------------------

    private record Fx(UUID coach, UUID coachUser, UUID studentUser, UUID student, UUID cycle, UUID attendance) {
    }

    private int eventCounter;

    private Fx fixture() {
        UUID coach = jdbc.queryForObject("INSERT INTO coach (name, brand_name) VALUES ('c', 'c') RETURNING id", UUID.class);
        UUID coachUser = user(coach, "COACH");
        UUID studentUser = user(coach, "STUDENT");
        UUID student = jdbc.queryForObject("INSERT INTO student (coach_id, full_name, email) VALUES (?, 's', ?) RETURNING id",
                UUID.class, coach, UUID.randomUUID() + "@test.co");
        UUID plan = jdbc.queryForObject("INSERT INTO plan (coach_id, name, classes_included, price_cop, modality) "
                + "VALUES (?, 'p', 8, 1, 'PERSONALIZED') RETURNING id", UUID.class, coach);
        UUID cycle = cycle(coach, student, plan, "ACTIVE", "2026-10-06", "2026-11-06");
        return new Fx(coach, coachUser, studentUser, student, cycle, null);
    }

    private UUID user(UUID coach, String role) {
        return jdbc.queryForObject("INSERT INTO app_user (coach_id, email, password_hash, role) VALUES (?, ?, 'h', ?) RETURNING id",
                UUID.class, coach, UUID.randomUUID() + "@test.co", role);
    }

    private UUID cycle(UUID coach, UUID student, UUID plan, String status, String start, String end) {
        return jdbc.queryForObject("INSERT INTO cycle (coach_id, student_id, plan_id, start_date, end_date, original_end_date, "
                + "classes_included, status, modality, closed_at) VALUES (?, ?, ?, ?::date, ?::date, ?::date, 8, ?, 'PERSONALIZED', "
                + "CASE WHEN ? = 'ACTIVE' THEN NULL ELSE now() END) RETURNING id", UUID.class, coach, student, plan, start, end, end, status, status);
    }

    /** A new SCHEDULED attendance in its own event (different hour each time: a coach cannot overlap events). */
    private UUID attendance(Fx f) {
        int hour = ++eventCounter;
        UUID event = jdbc.queryForObject("INSERT INTO class_session (coach_id, starts_at, ends_at, modality, capacity, status, created_by) "
                + "VALUES (?, ('2026-10-12 00:00+00'::timestamptz + (? || ' hours')::interval), ('2026-10-12 00:30+00'::timestamptz + (? || ' hours')::interval), "
                + "'PERSONALIZED', 1, 'SCHEDULED', ?) RETURNING id", UUID.class, f.coach(), hour, hour, f.coachUser());
        return jdbc.queryForObject("INSERT INTO session_attendance (coach_id, session_id, student_id, cycle_id, status, created_by) "
                + "VALUES (?, ?, ?, ?, 'SCHEDULED', ?) RETURNING id", UUID.class, f.coach(), event, f.student(), f.cycle(), f.coachUser());
    }

    private static String sqlState(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            Throwable t = e;
            while (t.getCause() != null) {
                t = t.getCause();
            }
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
            throw e;
        }
        return "NO-ERROR";
    }

    private void audit(Fx f, UUID attendance, String action, String prev, String next, String method, UUID actor, String role, String reason) {
        jdbc.update("INSERT INTO attendance_audit (coach_id, attendance_id, action, previous_status, new_status, method, actor_user_id, actor_role, reason) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", f.coach(), attendance, action, prev, next, method, actor, role, reason);
    }

    // ---- ck_audit_shape --------------------------------------------------------------------------------------

    @Test
    void everyRealFlowIsStored() {
        Fx f = fixture();
        UUID a = attendance(f);
        audit(f, a, "BOOK", null, "SCHEDULED", "COACH", f.coachUser(), "COACH", null);
        audit(f, a, "BOOK", null, "SCHEDULED", "STUDENT", f.studentUser(), "STUDENT", null);
        audit(f, a, "MARK", "SCHEDULED", "ATTENDED", "COACH", f.coachUser(), "COACH", null);
        audit(f, a, "MARK", "NO_SHOW", "ATTENDED", "COACH", f.coachUser(), "COACH", null);
        audit(f, a, "MARK", "SCHEDULED", "ATTENDED", "QR", f.studentUser(), "STUDENT", null);
        audit(f, a, "CANCEL", "SCHEDULED", "CANCELLED_ON_TIME", "STUDENT", f.studentUser(), "STUDENT", null);
        audit(f, a, "CANCEL", "SCHEDULED", "CANCELLED_BY_COACH", "COACH", f.coachUser(), "COACH", "lluvia");
        audit(f, a, "RESCHEDULE", "SCHEDULED", "RESCHEDULED", "STUDENT", f.studentUser(), "STUDENT", null);
        audit(f, a, "CONFIRM", "ATTENDED", "ATTENDED", "LATER", f.studentUser(), "STUDENT", null);
        audit(f, a, "CONFIRM", "SCHEDULED", "SCHEDULED", "QR", f.studentUser(), "STUDENT", null);
        audit(f, a, "TRANSFER", "SCHEDULED", "SCHEDULED", "COACH", f.coachUser(), "COACH", "renovacion de ciclo");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_audit WHERE attendance_id = ?", Integer.class, a)).isEqualTo(11);
    }

    @Test
    void aMarkWithoutAPreviousStatusIsRejectedEvenThoughACheckPassesOnNull() {
        Fx f = fixture();
        UUID a = attendance(f);
        assertThat(sqlState(() -> audit(f, a, "MARK", null, "ATTENDED", "COACH", f.coachUser(), "COACH", null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "CANCEL", null, "CANCELLED_ON_TIME", "STUDENT", f.studentUser(), "STUDENT", null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "CONFIRM", null, "ATTENDED", "LATER", f.studentUser(), "STUDENT", null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "TRANSFER", null, "SCHEDULED", "COACH", f.coachUser(), "COACH", "x"))).isEqualTo(CHECK);
    }

    @Test
    void theMostImportantInvalidCombinationsAreRejected() {
        Fx f = fixture();
        UUID a = attendance(f);
        UUID c = f.coachUser(), s = f.studentUser();
        // BOOK
        assertThat(sqlState(() -> audit(f, a, "BOOK", "SCHEDULED", "SCHEDULED", "COACH", c, "COACH", null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "BOOK", null, "SCHEDULED", "STUDENT", c, "COACH", null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "BOOK", null, "ATTENDED", "COACH", c, "COACH", null))).isEqualTo(CHECK);
        // MARK: a student can only scan, only to ATTENDED, only from SCHEDULED; the status must change
        assertThat(sqlState(() -> audit(f, a, "MARK", "SCHEDULED", "ATTENDED", "STUDENT", s, "STUDENT", null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "MARK", "SCHEDULED", "NO_SHOW", "QR", s, "STUDENT", null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "MARK", "NO_SHOW", "ATTENDED", "QR", s, "STUDENT", null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "MARK", "SCHEDULED", "ATTENDED", "QR", c, "COACH", null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "MARK", "ATTENDED", "ATTENDED", "COACH", c, "COACH", null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "MARK", "SCHEDULED", "CANCELLED_ON_TIME", "COACH", c, "COACH", null))).isEqualTo(CHECK);
        // CANCEL
        assertThat(sqlState(() -> audit(f, a, "CANCEL", "SCHEDULED", "CANCELLED_BY_COACH", "COACH", c, "COACH", null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "CANCEL", "SCHEDULED", "CANCELLED_BY_COACH", "COACH", c, "COACH", "  "))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "CANCEL", "SCHEDULED", "CANCELLED_ON_TIME", "COACH", c, "COACH", "x"))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "CANCEL", "SCHEDULED", "CANCELLED_BY_COACH", "STUDENT", s, "STUDENT", "x"))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "CANCEL", "ATTENDED", "CANCELLED_BY_COACH", "COACH", c, "COACH", "x"))).isEqualTo(CHECK);
        // RESCHEDULE: student only
        assertThat(sqlState(() -> audit(f, a, "RESCHEDULE", "SCHEDULED", "RESCHEDULED", "COACH", c, "COACH", null))).isEqualTo(CHECK);
        // CONFIRM: student only, never changes the status, live places only
        assertThat(sqlState(() -> audit(f, a, "CONFIRM", "SCHEDULED", "ATTENDED", "QR", s, "STUDENT", null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "CONFIRM", "ATTENDED", "ATTENDED", "COACH", c, "COACH", null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "CONFIRM", "CANCELLED_ON_TIME", "CANCELLED_ON_TIME", "LATER", s, "STUDENT", null))).isEqualTo(CHECK);
        // TRANSFER: the coach, status unchanged, reason required
        assertThat(sqlState(() -> audit(f, a, "TRANSFER", "SCHEDULED", "SCHEDULED", "COACH", c, "COACH", null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "TRANSFER", "SCHEDULED", "SCHEDULED", "COACH", c, "COACH", " "))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "TRANSFER", "SCHEDULED", "SCHEDULED", "STUDENT", s, "STUDENT", "x"))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "TRANSFER", "SCHEDULED", "ATTENDED", "COACH", c, "COACH", "x"))).isEqualTo(CHECK);
        assertThat(sqlState(() -> audit(f, a, "TRANSFER", "ATTENDED", "ATTENDED", "COACH", c, "COACH", "x"))).isEqualTo(CHECK);
        // unknown action
        assertThat(sqlState(() -> audit(f, a, "EDIT", "SCHEDULED", "SCHEDULED", "COACH", c, "COACH", "x"))).isEqualTo(CHECK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_audit WHERE attendance_id = ?", Integer.class, a)).isZero();
    }

    @Test
    void theAuditIsImmutableAndBoundToItsCoach() {
        Fx f = fixture();
        Fx other = fixture();
        UUID a = attendance(f);
        audit(f, a, "BOOK", null, "SCHEDULED", "COACH", f.coachUser(), "COACH", null);
        assertThat(sqlState(() -> jdbc.update("UPDATE attendance_audit SET reason = 'x' WHERE attendance_id = ?", a))).isEqualTo(IMMUTABLE);
        assertThat(sqlState(() -> jdbc.update("DELETE FROM attendance_audit WHERE attendance_id = ?", a))).isEqualTo(IMMUTABLE);
        assertThat(sqlState(() -> jdbc.execute("TRUNCATE attendance_audit"))).isEqualTo(IMMUTABLE);
        // an audit row of another coach pointing at this attendance
        assertThat(sqlState(() -> jdbc.update("INSERT INTO attendance_audit (coach_id, attendance_id, action, previous_status, new_status, method, "
                + "actor_user_id, actor_role) VALUES (?, ?, 'BOOK', NULL, 'SCHEDULED', 'COACH', ?, 'COACH')", other.coach(), a, other.coachUser())))
                .isEqualTo(FK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_audit WHERE attendance_id = ?", Integer.class, a)).isEqualTo(1);
    }

    // ---- the student confirmation: only those two columns are protected -----------------------------------------

    @Test
    void aConfirmedAttendanceKeepsWorkingForStatusCycleAndCancellation() {
        Fx f = fixture();
        UUID plan = jdbc.queryForObject("SELECT plan_id FROM cycle WHERE id = ?", UUID.class, f.cycle());
        UUID otherCycle = cycle(f.coach(), f.student(), plan, "EXPIRED", "2026-08-01", "2026-09-01");

        UUID marked = attendance(f);
        jdbc.update("UPDATE session_attendance SET student_confirmed_at = now(), student_confirmation_method = 'LATER' WHERE id = ?", marked);
        assertThat(sqlState(() -> jdbc.update("UPDATE session_attendance SET status = 'ATTENDED', marked_by = ?, marked_at = now() WHERE id = ?",
                f.coachUser(), marked))).isEqualTo("NO-ERROR");                                              // status

        UUID moved = attendance(f);
        jdbc.update("UPDATE session_attendance SET student_confirmed_at = now(), student_confirmation_method = 'QR' WHERE id = ?", moved);
        assertThat(sqlState(() -> jdbc.update("UPDATE session_attendance SET cycle_id = ? WHERE id = ?", otherCycle, moved)))
                .isEqualTo("NO-ERROR");                                                                      // cycle

        UUID cancelled = attendance(f);
        jdbc.update("UPDATE session_attendance SET student_confirmed_at = now(), student_confirmation_method = 'LATER' WHERE id = ?", cancelled);
        assertThat(sqlState(() -> jdbc.update("UPDATE session_attendance SET status = 'CANCELLED_BY_COACH', cancelled_by = ?, cancelled_at = now(), "
                + "cancel_reason = 'perdonada' WHERE id = ?", f.coachUser(), cancelled))).isEqualTo("NO-ERROR");   // cancellation

        for (UUID id : new UUID[] {marked, moved, cancelled}) {   // and the confirmation survived all of that
            assertThat(jdbc.queryForObject("SELECT student_confirmed_at IS NOT NULL FROM session_attendance WHERE id = ?", Boolean.class, id)).isTrue();
        }
    }

    @Test
    void aConfirmationCanNeitherChangeNorDisappear() {
        Fx f = fixture();
        UUID a = attendance(f);
        assertThat(sqlState(() -> jdbc.update("UPDATE session_attendance SET student_confirmed_at = now() WHERE id = ?", a))).isEqualTo(CHECK);
        assertThat(sqlState(() -> jdbc.update("UPDATE session_attendance SET student_confirmed_at = now(), student_confirmation_method = 'SMS' WHERE id = ?", a)))
                .isEqualTo(CHECK);
        jdbc.update("UPDATE session_attendance SET student_confirmed_at = '2026-10-12 10:00+00', student_confirmation_method = 'LATER' WHERE id = ?", a);
        assertThat(sqlState(() -> jdbc.update("UPDATE session_attendance SET student_confirmation_method = 'QR' WHERE id = ?", a))).isEqualTo(IMMUTABLE);
        assertThat(sqlState(() -> jdbc.update("UPDATE session_attendance SET student_confirmed_at = now() WHERE id = ?", a))).isEqualTo(IMMUTABLE);
        assertThat(sqlState(() -> jdbc.update("UPDATE session_attendance SET student_confirmed_at = NULL, student_confirmation_method = NULL WHERE id = ?", a)))
                .isEqualTo(IMMUTABLE);
        assertThat(jdbc.queryForObject("SELECT student_confirmation_method FROM session_attendance WHERE id = ?", String.class, a)).isEqualTo("LATER");
        // writing the same values again is not a change
        assertThat(sqlState(() -> jdbc.update("UPDATE session_attendance SET student_confirmed_at = '2026-10-12 10:00+00', "
                + "student_confirmation_method = 'LATER' WHERE id = ?", a))).isEqualTo("NO-ERROR");
    }

    // ---- consent_record / consent_revocation ----------------------------------------------------------------------

    private void accept(Fx f, String type, String version, String hash, String signerName, String signerRelationship) {
        jdbc.update("INSERT INTO consent_record (coach_id, student_id, type, version, text_sha256, signer_name, signer_relationship, "
                + "accepted_at, accepted_by_user_id) VALUES (?, ?, ?, ?, ?, ?, ?, '2026-10-01 10:00+00', ?)",
                f.coach(), f.student(), type, version, hash, signerName, signerRelationship, f.studentUser());
    }

    @Test
    void theSignerIsRequiredForTheGuardianAuthorizationAndForbiddenForTheOthers() {
        Fx f = fixture();
        accept(f, "DATA_GUARDIAN", "v1", HASH, "Marta Perez", "madre");
        accept(f, "DATA_ADULT", "v1", HASH, null, null);
        accept(f, "WHATSAPP", "v1", HASH, null, null);
        assertThat(sqlState(() -> accept(f, "DATA_GUARDIAN", "v2", HASH, null, null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> accept(f, "DATA_GUARDIAN", "v2", HASH, "Marta", null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> accept(f, "DATA_GUARDIAN", "v2", HASH, null, "madre"))).isEqualTo(CHECK);
        assertThat(sqlState(() -> accept(f, "DATA_GUARDIAN", "v2", HASH, " ", "madre"))).isEqualTo(CHECK);
        assertThat(sqlState(() -> accept(f, "DATA_GUARDIAN", "v2", HASH, "Marta", " "))).isEqualTo(CHECK);
        assertThat(sqlState(() -> accept(f, "DATA_ADULT", "v2", HASH, "Marta", "madre"))).isEqualTo(CHECK);
        assertThat(sqlState(() -> accept(f, "WHATSAPP", "v2", HASH, "Marta", null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> accept(f, "WHATSAPP", "v2", HASH, null, "madre"))).isEqualTo(CHECK);
    }

    @Test
    void theSameVersionCanBeAcceptedAgainAfterARevocation() {
        Fx f = fixture();
        accept(f, "WHATSAPP", "v1", HASH, null, null);
        jdbc.update("INSERT INTO consent_revocation (coach_id, student_id, type, revoked_at, revoked_by_user_id) VALUES (?, ?, 'WHATSAPP', '2026-10-02 10:00+00', ?)",
                f.coach(), f.student(), f.studentUser());
        assertThat(sqlState(() -> accept(f, "WHATSAPP", "v1", HASH, null, null))).isEqualTo("NO-ERROR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consent_record WHERE student_id = ? AND type = 'WHATSAPP' AND version = 'v1'",
                Integer.class, f.student())).isEqualTo(2);
    }

    @Test
    void consentRowsAreValidatedImmutableAndBoundToTheirCoach() {
        Fx f = fixture();
        Fx other = fixture();
        accept(f, "WHATSAPP", "v1", HASH, null, null);
        jdbc.update("INSERT INTO consent_revocation (coach_id, student_id, type, revoked_by_user_id) VALUES (?, ?, 'WHATSAPP', ?)",
                f.coach(), f.student(), f.studentUser());
        assertThat(sqlState(() -> accept(f, "MARKETING", "v1", HASH, null, null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> accept(f, "WHATSAPP", "v1", HASH.toUpperCase(), null, null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> accept(f, "WHATSAPP", "v1", "abc", null, null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> jdbc.update("INSERT INTO consent_revocation (coach_id, student_id, type, revoked_by_user_id) VALUES (?, ?, 'PHOTOS', ?)",
                f.coach(), f.student(), f.studentUser()))).isEqualTo(CHECK);
        for (String table : new String[] {"consent_record", "consent_revocation"}) {
            assertThat(sqlState(() -> jdbc.update("UPDATE " + table + " SET type = type WHERE student_id = ?", f.student()))).isEqualTo(IMMUTABLE);
            assertThat(sqlState(() -> jdbc.update("DELETE FROM " + table + " WHERE student_id = ?", f.student()))).isEqualTo(IMMUTABLE);
            assertThat(sqlState(() -> jdbc.execute("TRUNCATE " + table))).isEqualTo(IMMUTABLE);
        }
        // another coach cannot write consents for this coach's student
        assertThat(sqlState(() -> jdbc.update("INSERT INTO consent_record (coach_id, student_id, type, version, text_sha256, accepted_by_user_id) "
                + "VALUES (?, ?, 'WHATSAPP', 'v1', ?, ?)", other.coach(), f.student(), HASH, other.studentUser()))).isEqualTo(FK);
        assertThat(sqlState(() -> jdbc.update("INSERT INTO consent_revocation (coach_id, student_id, type, revoked_by_user_id) VALUES (?, ?, 'WHATSAPP', ?)",
                other.coach(), f.student(), other.studentUser()))).isEqualTo(FK);
    }

    @Test
    void timestampsComeFromTheCallerAndTheDefaultIsOnlyABackup() {
        Fx f = fixture();
        accept(f, "WHATSAPP", "v1", HASH, null, null);   // explicit accepted_at = 2026-10-01 10:00 UTC
        assertThat(jdbc.queryForObject("SELECT accepted_at = '2026-10-01 10:00+00'::timestamptz FROM consent_record WHERE student_id = ?",
                Boolean.class, f.student())).isTrue();
    }

    @Test
    void noIpIsStoredAnywhere() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name IN ('consent_record', 'consent_revocation', 'attendance_audit') AND column_name ~* '(^|_)ip(_|$)'", Integer.class)).isZero();
    }

    @Test
    void rowLevelSecurityIsOnForTheNewTablesWithoutPolicies() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_class WHERE relname IN ('consent_record', 'consent_revocation', 'attendance_audit') "
                + "AND relrowsecurity", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_policies WHERE tablename IN ('consent_record', 'consent_revocation', 'attendance_audit')",
                Integer.class)).isZero();
    }
}
