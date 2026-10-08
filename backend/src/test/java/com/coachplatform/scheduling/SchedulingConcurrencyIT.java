package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.auth.AppUserRepository;
import com.coachplatform.auth.AuthDtos.RegisterCoachRequest;
import com.coachplatform.auth.AuthService;
import com.coachplatform.billing.BillingService;
import com.coachplatform.billing.PlanService;
import com.coachplatform.billing.api.PaymentMethod;
import com.coachplatform.billing.api.PlanInput;
import com.coachplatform.billing.api.RegisterPaymentCommand;
import com.coachplatform.scheduling.api.SessionStatus;
import com.coachplatform.scheduling.api.SessionSummary;
import com.coachplatform.scheduling.api.WindowInput;
import com.coachplatform.scheduling.domain.SchedulingRuleException;
import com.coachplatform.scheduling.domain.SchedulingRuleException.Code;
import com.coachplatform.students.StudentService;
import com.coachplatform.students.api.StudentInput;
import com.coachplatform.support.PostgresIntegrationTest;
import com.coachplatform.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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

/** Scheduling under real concurrency against real PostgreSQL (row locks + the no-overlap exclusion constraint). */
class SchedulingConcurrencyIT extends PostgresIntegrationTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    @Autowired AuthService auth;
    @Autowired AppUserRepository users;
    @Autowired StudentService students;
    @Autowired PlanService plans;
    @Autowired BillingService billing;
    @Autowired AvailabilityService availability;
    @Autowired SchedulingService scheduling;
    @Autowired JdbcTemplate jdbc;

    record Coach(UUID coachId, UUID userId, UUID planId) {
    }

    private Coach coach(int planClasses) {
        String email = "coach-" + UUID.randomUUID() + "@test.co";
        UUID coachId = auth.registerCoach(new RegisterCoachRequest("Coach", email, "Prueba-1234-x")).coachId();
        UUID userId = users.findByEmailIgnoreCase(email).orElseThrow().getId();
        UUID planId = TenantContext.callAs(coachId, () -> {
            List<WindowInput> week = new ArrayList<>();
            for (int d = 1; d <= 7; d++) {
                week.add(new WindowInput(d, "06:00", "20:00"));
            }
            availability.replaceWeekly(week);
            return plans.create(new PlanInput(planClasses + " clases", planClasses, 520_000)).id();
        });
        return new Coach(coachId, userId, planId);
    }

    private UUID studentWithCycle(Coach c) {
        return TenantContext.callAs(c.coachId(), () -> {
            UUID id = students.create(new StudentInput("Alumno", "a-" + UUID.randomUUID() + "@test.co", null), c.userId()).student().id();
            billing.registerPayment(id, new RegisterPaymentCommand(c.planId(), null, PaymentMethod.CASH, null), c.userId());
            return id;
        });
    }

    /** A bookable instant: N days ahead (Bogota) at the given hour. */
    private static Instant slot(int daysAhead, int hour) {
        return LocalDate.now(BOGOTA).plusDays(daysAhead).atTime(LocalTime.of(hour, 0)).atZone(BOGOTA).toInstant();
    }

    private Object attempt(Coach c, Callable<Object> action) {
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
    }

    private List<Object> race(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
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

    private static void assertRuleCode(Object result, Code expected) {
        assertThat(result).isInstanceOf(SchedulingRuleException.class);
        assertThat(((SchedulingRuleException) result).code()).isEqualTo(expected);
    }

    @Test
    void twoStudentsRacingForTheSameSlotOnlyOneGetsIt() throws Exception {
        for (int round = 0; round < 5; round++) {
            Coach c = coach(8);
            UUID ana = studentWithCycle(c);
            UUID beto = studentWithCycle(c);
            Instant when = slot(3 + round, 10);

            List<Object> results = race(List.of(
                    () -> attempt(c, () -> scheduling.bookAsCoach(ana, when, c.userId())),
                    () -> attempt(c, () -> scheduling.bookAsCoach(beto, when, c.userId()))));

            assertThat(results.stream().filter(r -> r instanceof SessionSummary)).as("winners").hasSize(1);
            results.stream().filter(r -> !(r instanceof SessionSummary)).forEach(r -> assertRuleCode(r, Code.SLOT_TAKEN));
            assertThat(jdbc.queryForObject("select count(*) from class_session where coach_id = ? and status = 'SCHEDULED'",
                    Integer.class, c.coachId())).isEqualTo(1);
        }
    }

    @Test
    void oneStudentBookingManySlotsAtOnceNeverExceedsTheCycleQuota() throws Exception {
        Coach c = coach(2);
        UUID ana = studentWithCycle(c);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int hour = 8; hour <= 13; hour++) {
            Instant when = slot(4, hour);
            tasks.add(() -> attempt(c, () -> scheduling.bookAsCoach(ana, when, c.userId())));
        }

        List<Object> results = race(tasks);

        assertThat(results.stream().filter(r -> r instanceof SessionSummary)).as("accepted").hasSize(2);
        results.stream().filter(r -> !(r instanceof SessionSummary)).forEach(r -> assertRuleCode(r, Code.QUOTA_EXCEEDED));
        assertThat(jdbc.queryForObject("select count(*) from class_session where student_id = ? and status = 'SCHEDULED'",
                Integer.class, ana)).isEqualTo(2);
    }

    @Test
    void markingAndCancellingTheSameStartedClassAtOnceOnlyOneWins() throws Exception {
        for (int round = 0; round < 5; round++) {
            Coach c = coach(8);
            UUID ana = studentWithCycle(c);
            UUID cycleId = jdbc.queryForObject("select id from cycle where student_id = ?", UUID.class, ana);
            // a class that already started and is still unmarked (cannot be booked through the API: it is in the past)
            UUID session = jdbc.queryForObject("insert into class_session (coach_id, student_id, cycle_id, starts_at, ends_at, status, created_by) "
                    + "values (?, ?, ?, now() - interval '2 hours', now() - interval '1 hour', 'SCHEDULED', ?) returning id",
                    UUID.class, c.coachId(), ana, cycleId, c.userId());

            List<Object> results = race(List.of(
                    () -> attempt(c, () -> scheduling.markAttendance(session, SessionStatus.ATTENDED, c.userId())),
                    () -> attempt(c, () -> scheduling.cancelAsCoach(session, "Perdonada", null, c.userId()))));

            long winners = results.stream().filter(r -> !(r instanceof Throwable)).count();
            assertThat(winners).as("exactly one of mark / cancel").isEqualTo(1);
            results.stream().filter(r -> r instanceof Throwable).forEach(r -> assertRuleCode(r, Code.INVALID_STATE));

            String status = jdbc.queryForObject("select status from class_session where id = ?", String.class, session);
            int used = jdbc.queryForObject("select classes_used from cycle where id = ?", Integer.class, cycleId);
            // consistent: it was either marked (and used a class) or cancelled (and used none), never both
            assertThat(status.equals("ATTENDED") ? used == 1 : used == 0 && status.equals("CANCELLED_BY_COACH")).isTrue();
        }
    }

    @Test
    void theLastClassMarkedByTwoRequestsCompletesTheCycleOnce() throws Exception {
        Coach c = coach(1);
        UUID ana = studentWithCycle(c);
        UUID cycleId = jdbc.queryForObject("select id from cycle where student_id = ?", UUID.class, ana);
        UUID session = jdbc.queryForObject("insert into class_session (coach_id, student_id, cycle_id, starts_at, ends_at, status, created_by) "
                + "values (?, ?, ?, now() - interval '2 hours', now() - interval '1 hour', 'SCHEDULED', ?) returning id",
                UUID.class, c.coachId(), ana, cycleId, c.userId());

        List<Object> results = race(List.of(
                () -> attempt(c, () -> scheduling.markAttendance(session, SessionStatus.ATTENDED, c.userId())),
                () -> attempt(c, () -> scheduling.markAttendance(session, SessionStatus.ATTENDED, c.userId()))));

        assertThat(results.stream().filter(r -> r instanceof SessionSummary)).hasSize(1);
        results.stream().filter(r -> r instanceof Throwable).forEach(r -> assertThat(r).isInstanceOf(Exception.class));
        assertThat(jdbc.queryForObject("select classes_used from cycle where id = ?", Integer.class, cycleId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from cycle where id = ?", String.class, cycleId)).isEqualTo("COMPLETED");
    }
}
