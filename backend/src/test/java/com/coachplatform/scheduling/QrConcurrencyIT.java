package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.auth.AppUserRepository;
import com.coachplatform.auth.AuthDtos.RegisterCoachRequest;
import com.coachplatform.auth.AuthService;
import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.api.ConfirmQrResult;
import com.coachplatform.scheduling.domain.SchedulingRuleException;
import com.coachplatform.support.PostgresIntegrationTest;
import com.coachplatform.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Against real PostgreSQL: the student's row lock serializes everything that marks or confirms ONE attendance, so a class is used
 * up exactly once and its audit has exactly one MARK and one CONFIRM, whoever gets there first.
 * (Fixtures are inserted by SQL; the class started ten minutes ago, so a scan is valid right now.)
 */
class QrConcurrencyIT extends PostgresIntegrationTest {

    @Autowired AuthService auth;
    @Autowired AppUserRepository users;
    @Autowired AttendanceConfirmationService confirmations;
    @Autowired SchedulingService scheduling;
    @Autowired JdbcTemplate jdbc;

    private record Ctx(UUID coachId, UUID coachUser, UUID studentUser, UUID studentId, UUID cycleId, UUID eventId, UUID attendanceId) {
    }

    private Ctx startedClass() {
        String email = "coach-" + UUID.randomUUID() + "@test.co";
        UUID coachId = auth.registerCoach(new RegisterCoachRequest("Coach", email, "Prueba-1234-x")).coachId();
        UUID coachUser = users.findByEmailIgnoreCase(email).orElseThrow().getId();
        UUID studentUser = jdbc.queryForObject("INSERT INTO app_user (coach_id, email, password_hash, role) VALUES (?, ?, 'h', 'STUDENT') RETURNING id",
                UUID.class, coachId, "alumno-" + UUID.randomUUID() + "@test.co");
        UUID studentId = jdbc.queryForObject("INSERT INTO student (coach_id, full_name, email, user_id, birth_date) VALUES (?, 'Ana', ?, ?, '1990-05-01') RETURNING id",
                UUID.class, coachId, "ana-" + UUID.randomUUID() + "@test.co", studentUser);
        UUID plan = jdbc.queryForObject("INSERT INTO plan (coach_id, name, classes_included, price_cop, modality) VALUES (?, 'p', 8, 1, 'PERSONALIZED') RETURNING id",
                UUID.class, coachId);
        LocalDate today = LocalDate.now(ZoneId.of("America/Bogota"));
        UUID cycle = jdbc.queryForObject("INSERT INTO cycle (coach_id, student_id, plan_id, start_date, end_date, original_end_date, classes_included, status, modality) "
                + "VALUES (?, ?, ?, ?, ?, ?, 8, 'ACTIVE', 'PERSONALIZED') RETURNING id", UUID.class, coachId, studentId, plan,
                today.minusDays(2), today.plusDays(28), today.plusDays(28));
        Instant start = Instant.now().minusSeconds(600);
        UUID event = jdbc.queryForObject("INSERT INTO class_session (coach_id, starts_at, ends_at, modality, capacity, status, created_by) "
                + "VALUES (?, ?::timestamptz, ?::timestamptz, 'PERSONALIZED', 1, 'SCHEDULED', ?) RETURNING id", UUID.class, coachId,
                start.toString(), start.plusSeconds(3600).toString(), coachUser);
        UUID attendance = jdbc.queryForObject("INSERT INTO session_attendance (coach_id, session_id, student_id, cycle_id, status, created_by) "
                + "VALUES (?, ?, ?, ?, 'SCHEDULED', ?) RETURNING id", UUID.class, coachId, event, studentId, cycle, coachUser);
        return new Ctx(coachId, coachUser, studentUser, studentId, cycle, event, attendance);
    }

    private String token(Ctx c) {
        return TenantContext.callAs(c.coachId(), () -> confirmations.issueQr(c.eventId()).token());
    }

    /** Runs the actions at the same instant, each one as the coach's tenant; returns the result or the exception of each. */
    private List<Object> race(Ctx c, List<Callable<Object>> actions) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        CountDownLatch ready = new CountDownLatch(actions.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> action : actions) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    return TenantContext.callAs(c.coachId(), () -> {
                        try {
                            return action.call();
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        ready.await();
        go.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    private int classesUsed(Ctx c) {
        return jdbc.queryForObject("select classes_used from cycle where id = ?", Integer.class, c.cycleId());
    }

    private int auditCount(Ctx c, String action) {
        return jdbc.queryForObject("select count(*) from attendance_audit where attendance_id = ? and action = ?", Integer.class, c.attendanceId(), action);
    }

    private String status(Ctx c) {
        return jdbc.queryForObject("select status from session_attendance where id = ?", String.class, c.attendanceId());
    }

    private String confirmationMethod(Ctx c) {
        return jdbc.queryForObject("select student_confirmation_method from session_attendance where id = ?", String.class, c.attendanceId());
    }

    @Test
    void twoSimultaneousScansOfTheSameCodeUseTheClassOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            Ctx c = startedClass();
            String token = token(c);
            Callable<Object> scan = () -> confirmations.confirmByQr(c.studentUser(), token);
            List<Object> results = race(c, List.of(scan, scan));

            assertThat(results).allMatch(r -> r instanceof ConfirmQrResult).as("neither scan fails");
            List<ConfirmQrResult> scans = results.stream().map(r -> (ConfirmQrResult) r).toList();
            assertThat(scans.stream().filter(ConfirmQrResult::markedAttended)).as("exactly one scan marked the class").hasSize(1);
            assertThat(scans.stream().filter(ConfirmQrResult::alreadyConfirmed)).as("the other one found it already confirmed").hasSize(1);
            assertThat(classesUsed(c)).isEqualTo(1);
            assertThat(status(c)).isEqualTo("ATTENDED");
            assertThat(confirmationMethod(c)).isEqualTo("QR");
            assertThat(auditCount(c, "MARK")).isEqualTo(1);
            assertThat(auditCount(c, "CONFIRM")).isEqualTo(1);
        }
    }

    @Test
    void aScanWhileTheCoachMarksTheSameAttendanceNeverUsesTheClassTwice() throws Exception {
        for (int round = 0; round < 5; round++) {
            Ctx c = startedClass();
            String token = token(c);
            List<Object> results = race(c, List.of(
                    () -> confirmations.confirmByQr(c.studentUser(), token),
                    () -> scheduling.markAttendance(c.attendanceId(), AttendanceStatus.ATTENDED, c.coachUser())));

            assertThat(results.get(0)).as("the scan always succeeds").isInstanceOf(ConfirmQrResult.class);
            Object coach = results.get(1);
            assertThat(coach instanceof Throwable ? ((SchedulingRuleException) coach).code() : null)
                    .as("the coach either marked it first, or found it already marked by the scan")
                    .isIn(null, SchedulingRuleException.Code.ALREADY_MARKED);
            assertThat(classesUsed(c)).as("the class was used up exactly once").isEqualTo(1);
            assertThat(status(c)).isEqualTo("ATTENDED");
            assertThat(confirmationMethod(c)).isEqualTo("QR");
            assertThat(auditCount(c, "MARK")).isEqualTo(1);
            assertThat(auditCount(c, "CONFIRM")).isEqualTo(1);
        }
    }

    @Test
    void aScanWhileTheCoachMarksANoShowEndsAsNoShowConfirmedAndUsedOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            Ctx c = startedClass();
            String token = token(c);
            List<Object> results = race(c, List.of(
                    () -> confirmations.confirmByQr(c.studentUser(), token),
                    () -> scheduling.markAttendance(c.attendanceId(), AttendanceStatus.NO_SHOW, c.coachUser())));

            assertThat(results).allMatch(r -> !(r instanceof Throwable));
            assertThat(status(c)).as("the coach's NO_SHOW is never overwritten by the scan, and a scan that came first is flipped by the coach")
                    .isEqualTo("NO_SHOW");
            assertThat(classesUsed(c)).isEqualTo(1);
            assertThat(confirmationMethod(c)).isEqualTo("QR");
            assertThat(auditCount(c, "CONFIRM")).isEqualTo(1);
        }
    }
}
