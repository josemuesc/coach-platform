package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.auth.AppUserRepository;
import com.coachplatform.auth.AuthDtos.RegisterCoachRequest;
import com.coachplatform.auth.AuthService;
import com.coachplatform.billing.BillingService;
import com.coachplatform.billing.PlanService;
import com.coachplatform.billing.api.Modality;
import com.coachplatform.billing.api.PaymentMethod;
import com.coachplatform.billing.api.PlanInput;
import com.coachplatform.billing.api.RegisterPaymentCommand;
import com.coachplatform.billing.domain.CycleRuleException;
import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.api.AttendanceView;
import com.coachplatform.scheduling.api.WindowInput;
import com.coachplatform.students.StudentService;
import com.coachplatform.students.api.StudentInput;
import com.coachplatform.support.PostgresIntegrationTest;
import com.coachplatform.tenant.TenantContext;
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

/**
 * The renewal rule "a student who used their last class may pay at once" against real PostgreSQL, with the mark of that last class and
 * the payment arriving at the SAME moment. Both take the student's row lock first, so they serialize; whichever goes first, the result is
 * one of two consistent worlds and never a deadlock, a second active cycle or a half-closed one:
 * (a) the payment loses: it is refused cleanly (ACTIVE_CYCLE_EXISTS), the mark completes the cycle, and a second try then succeeds;
 * (b) the mark wins first: the cycle is COMPLETED and the payment opens the new one.
 */
class LastClassVersusPaymentConcurrencyIT extends PostgresIntegrationTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    @Autowired AuthService auth;
    @Autowired AppUserRepository users;
    @Autowired StudentService students;
    @Autowired PlanService plans;
    @Autowired BillingService billing;
    @Autowired AvailabilityService availability;
    @Autowired SchedulingService scheduling;
    @Autowired JdbcTemplate jdbc;

    private record World(UUID coachId, UUID userId, UUID planId, UUID studentId, UUID attendanceId, UUID eventId) {
    }

    /** A coach, a 1-class plan, a student who paid, and their only class booked and ALREADY STARTED (moved back in time by SQL). */
    private World world() {
        String email = "coach-" + UUID.randomUUID() + "@test.co";
        UUID coachId = auth.registerCoach(new RegisterCoachRequest("Coach", email, "Prueba-1234-x")).coachId();
        UUID userId = users.findByEmailIgnoreCase(email).orElseThrow().getId();
        return TenantContext.callAs(coachId, () -> {
            List<WindowInput> week = new ArrayList<>();
            for (int d = 1; d <= 7; d++) {
                week.add(new WindowInput(d, "06:00", "20:00"));
            }
            availability.replaceWeekly(week);
            UUID plan = plans.create(new PlanInput("una clase", 1, 100_000L, Modality.PERSONALIZED)).id();
            UUID student = students.create(new StudentInput("Alumno", "a-" + UUID.randomUUID() + "@test.co", null, LocalDate.of(1990, 5, 1), null, null), userId)
                    .student().id();
            billing.registerPayment(student, new RegisterPaymentCommand(plan, 100_000L, PaymentMethod.CASH, null), userId);
            var when = LocalDate.now(BOGOTA).plusDays(2).atTime(LocalTime.of(10, 0)).atZone(BOGOTA).toInstant();
            AttendanceView booked = scheduling.bookAsCoach(student, when, userId, false, null);
            jdbc.update("update class_session set starts_at = now() - interval '90 minutes', ends_at = now() - interval '30 minutes' where id = ?", booked.eventId());
            return new World(coachId, userId, plan, student, booked.id(), booked.eventId());
        });
    }

    private Object attempt(World w, Callable<Object> action) {
        try {
            return TenantContext.callAs(w.coachId(), () -> {
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

    private Object pay(World w) {
        return attempt(w, () -> billing.registerPayment(w.studentId(), new RegisterPaymentCommand(w.planId(), 100_000L, PaymentMethod.CASH, null), w.userId()));
    }

    private Object mark(World w) {
        return attempt(w, () -> scheduling.markAttendance(w.attendanceId(), AttendanceStatus.ATTENDED, w.userId()));
    }

    @Test
    void markingTheLastClassAndPayingAtTheSameTimeEndsInOneOfTwoConsistentWorldsNeverADeadlock() throws Exception {
        int paymentWon = 0;
        int paymentRefused = 0;
        for (int round = 0; round < 12; round++) {
            World w = world();
            List<Object> results = race(List.of(() -> mark(w), () -> pay(w)));
            Object marked = results.get(0);
            Object paid = results.get(1);

            assertThat(marked).as("the mark always succeeds: it is the one thing that never conflicts").isInstanceOf(AttendanceView.class);
            if (paid instanceof Throwable refused) {
                paymentRefused++;
                assertThat(refused).as("a clean refusal, not a deadlock / constraint error").isInstanceOf(CycleRuleException.class);
                assertThat(((CycleRuleException) refused).code()).isEqualTo(CycleRuleException.Code.ACTIVE_CYCLE_EXISTS);
                // the cycle is now COMPLETED, so the very same payment is accepted
                assertThat(pay(w)).isNotInstanceOf(Throwable.class);
            } else {
                paymentWon++;
            }
            // in both worlds: the old cycle COMPLETED with its 1 class used and its dates untouched; ONE new active cycle
            List<java.util.Map<String, Object>> cycles = jdbc.queryForList(
                    "select status, classes_used, classes_included, start_date, end_date from cycle where student_id = ? order by created_at", w.studentId());
            assertThat(cycles).hasSize(2);
            assertThat(cycles.get(0)).containsEntry("status", "COMPLETED").containsEntry("classes_used", 1);
            assertThat(cycles.get(1)).containsEntry("status", "ACTIVE").containsEntry("classes_used", 0);
            assertThat(cycles.stream().filter(c -> "ACTIVE".equals(c.get("status"))).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from payment where student_id = ?", Integer.class, w.studentId())).isEqualTo(2);
            assertThat(jdbc.queryForObject("select status from session_attendance where id = ?", String.class, w.attendanceId())).isEqualTo("ATTENDED");
        }
        // both orders happen across the rounds or at least both are legal; what matters is the invariants above held every time
        assertThat(paymentWon + paymentRefused).isEqualTo(12);
    }

    @Test
    void aClassThatStartedButWasNeverMarkedDoesNotCountAsUsedSoThePaymentIsRefused() {
        World w = world();                                                                     // started, unmarked
        Object paid = pay(w);
        assertThat(paid).isInstanceOf(CycleRuleException.class);
        assertThat(((CycleRuleException) paid).code()).isEqualTo(CycleRuleException.Code.ACTIVE_CYCLE_EXISTS);
        assertThat(jdbc.queryForObject("select status from cycle where student_id = ?", String.class, w.studentId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select classes_used from cycle where student_id = ?", Integer.class, w.studentId())).isZero();
    }
}
