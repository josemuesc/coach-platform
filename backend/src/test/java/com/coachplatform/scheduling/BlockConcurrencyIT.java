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
import com.coachplatform.common.ApiException;
import com.coachplatform.scheduling.api.AttendanceView;
import com.coachplatform.scheduling.api.BlockCreated;
import com.coachplatform.scheduling.api.BlockInput;
import com.coachplatform.scheduling.api.WindowInput;
import com.coachplatform.scheduling.domain.SchedulingRuleException;
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

/**
 * A block racing a booking, against real PostgreSQL. Whoever wins, the end state must be consistent: if the block exists, no class
 * is left SCHEDULED inside it; the loser gets a clean rejection (BLOCKED, BLOCK_AFFECTED_CHANGED or CONCURRENT_CHANGE), never a
 * deadlock or a constraint error. Locks are taken in the project's order (students, calendar, events by id).
 */
class BlockConcurrencyIT extends PostgresIntegrationTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final int ROUNDS = 8;

    @Autowired AuthService auth;
    @Autowired AppUserRepository users;
    @Autowired StudentService students;
    @Autowired PlanService plans;
    @Autowired BillingService billing;
    @Autowired AvailabilityService availability;
    @Autowired SchedulingService scheduling;
    @Autowired BlockService blockService;
    @Autowired JdbcTemplate jdbc;

    record Coach(UUID coachId, UUID userId, UUID personalizedPlan, UUID semiPlan) {
    }

    private Coach coach() {
        String email = "coach-" + UUID.randomUUID() + "@test.co";
        UUID coachId = auth.registerCoach(new RegisterCoachRequest("Coach", email, "Prueba-1234-x")).coachId();
        UUID userId = users.findByEmailIgnoreCase(email).orElseThrow().getId();
        return TenantContext.callAs(coachId, () -> {
            List<WindowInput> week = new ArrayList<>();
            for (int d = 1; d <= 7; d++) {
                week.add(new WindowInput(d, "06:00", "20:00"));
            }
            availability.replaceWeekly(week);
            return new Coach(coachId, userId, plans.create(new PlanInput("pers", 8, 520_000L, Modality.PERSONALIZED)).id(),
                    plans.create(new PlanInput("semi", 8, 320_000L, Modality.SEMI_PERSONALIZED)).id());
        });
    }

    private UUID student(Coach c, boolean semi) {
        return TenantContext.callAs(c.coachId(), () -> {
            UUID id = students.create(new StudentInput("Alumno", "a-" + UUID.randomUUID() + "@test.co", null, LocalDate.of(1990, 5, 1), null, null), c.userId()).student().id();
            billing.registerPayment(id, new RegisterPaymentCommand(semi ? c.semiPlan() : c.personalizedPlan(), 520_000L, PaymentMethod.CASH, null), c.userId());
            return id;
        });
    }

    private static LocalDate day(int daysAhead) {
        return LocalDate.now(BOGOTA).plusDays(daysAhead);
    }

    private static Instant at(int daysAhead, int hour) {
        return day(daysAhead).atTime(LocalTime.of(hour, 0)).atZone(BOGOTA).toInstant();
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

    private static void assertCleanLoser(Object result) {
        boolean clean = result instanceof AttendanceView || result instanceof BlockCreated
                || (result instanceof SchedulingRuleException e && e.code() == SchedulingRuleException.Code.BLOCKED)
                || (result instanceof ApiException e2 && (e2.code().equals("BLOCK_AFFECTED_CHANGED") || e2.code().equals("CONCURRENT_CHANGE")));
        assertThat(clean).as("a clean outcome, not a deadlock or constraint error: " + result).isTrue();
    }

    private int scheduledInside(Coach c, int daysAhead, int fromHour, int toHour) {
        return jdbc.queryForObject("select count(*) from session_attendance a join class_session s on s.id = a.session_id "
                + "where s.coach_id = ? and a.status = 'SCHEDULED' and s.starts_at < ? and s.ends_at > ?", Integer.class,
                c.coachId(), java.sql.Timestamp.from(at(daysAhead, toHour)), java.sql.Timestamp.from(at(daysAhead, fromHour)));
    }

    private int blocks(Coach c) {
        return jdbc.queryForObject("select count(*) from availability_block where coach_id = ?", Integer.class, c.coachId());
    }

    private BlockInput block(int daysAhead, List<UUID> affected) {
        return new BlockInput(day(daysAhead), false, "08:00", "12:00", "Reunión", affected);
    }

    @Test
    void aBlockRacingABookingThatCreatesAnEventNeverLeavesAClassInsideTheBlock() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Coach c = coach();
            UUID ana = student(c, false);
            List<Object> results = race(List.of(
                    () -> attempt(c, () -> scheduling.bookAsCoach(ana, at(3, 10), c.userId(), false, null)),
                    () -> attempt(c, () -> blockService.create(block(3, List.of()), c.userId()))));
            results.forEach(BlockConcurrencyIT::assertCleanLoser);
            if (blocks(c) > 0) {
                assertThat(scheduledInside(c, 3, 8, 12)).as("round " + round + ": the block is saved, so nothing may stay inside it").isZero();
            }
        }
    }

    @Test
    void aBlockRacingSomeoneJoiningAnExistingEventEitherSeesThemOrIsRefused() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Coach c = coach();
            UUID ana = student(c, true);
            UUID beto = student(c, true);
            AttendanceView anaPlace = (AttendanceView) attempt(c, () -> scheduling.bookAsCoach(ana, at(3, 10), c.userId(), false, null));
            List<Object> results = race(List.of(
                    () -> attempt(c, () -> scheduling.bookAsCoach(beto, at(3, 10), c.userId(), false, null)),
                    () -> attempt(c, () -> blockService.create(block(3, List.of(anaPlace.id())), c.userId()))));
            results.forEach(BlockConcurrencyIT::assertCleanLoser);
            if (blocks(c) > 0) {
                assertThat(scheduledInside(c, 3, 8, 12)).as("round " + round + ": nobody may stay SCHEDULED inside a saved block").isZero();
            }
        }
    }
}
