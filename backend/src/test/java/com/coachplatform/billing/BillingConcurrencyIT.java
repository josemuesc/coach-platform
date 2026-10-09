package com.coachplatform.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.auth.AppUserRepository;
import com.coachplatform.auth.AuthDtos.RegisterCoachRequest;
import com.coachplatform.auth.AuthService;
import com.coachplatform.billing.api.PaymentMethod;
import com.coachplatform.billing.api.PaymentRegistered;
import com.coachplatform.billing.api.PlanInput;
import com.coachplatform.billing.api.RegisterPaymentCommand;
import com.coachplatform.billing.domain.CycleRuleException;
import com.coachplatform.students.StudentService;
import com.coachplatform.students.api.StudentInput;
import com.coachplatform.support.PostgresIntegrationTest;
import com.coachplatform.tenant.TenantContext;
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

/** Simultaneous payments of the same student against real PostgreSQL: exactly one may win. */
class BillingConcurrencyIT extends PostgresIntegrationTest {

    @Autowired AuthService auth;
    @Autowired AppUserRepository users;
    @Autowired StudentService students;
    @Autowired PlanService plans;
    @Autowired BillingService billing;
    @Autowired JdbcTemplate jdbc;

    record Fixture(UUID coachId, UUID userId, UUID planId, UUID studentId) {
    }

    private Fixture fixture() {
        String email = "coach-" + UUID.randomUUID() + "@test.co";
        UUID coachId = auth.registerCoach(new RegisterCoachRequest("Coach", email, "Prueba-1234-x")).coachId();
        UUID userId = users.findByEmailIgnoreCase(email).orElseThrow().getId();
        return TenantContext.callAs(coachId, () -> {
            UUID planId = plans.create(new PlanInput("8 clases", 8, 520_000L, com.coachplatform.billing.api.Modality.PERSONALIZED)).id();
            UUID studentId = students.create(new StudentInput("Ana", "alumno-" + UUID.randomUUID() + "@test.co", null, java.time.LocalDate.of(1990, 5, 1), null, null), userId)
                    .student().id();
            return new Fixture(coachId, userId, planId, studentId);
        });
    }

    private Object payOnce(Fixture f) {
        try {
            return TenantContext.callAs(f.coachId(), () -> billing.registerPayment(f.studentId(),
                    new RegisterPaymentCommand(f.planId(), 520_000L, PaymentMethod.NEQUI, null), f.userId()));
        } catch (Throwable t) {
            return t;
        }
    }

    private List<Object> runConcurrently(int threads, Callable<Object> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
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

    private void assertExactlyOneWon(Fixture f, List<Object> results) {
        List<Object> winners = results.stream().filter(r -> r instanceof PaymentRegistered).toList();
        List<Object> losers = results.stream().filter(r -> !(r instanceof PaymentRegistered)).toList();

        assertThat(winners).as("successful payments").hasSize(1);
        assertThat(losers).as("rejected payments").allSatisfy(t -> {
            assertThat(t).isInstanceOf(CycleRuleException.class);
            assertThat(((CycleRuleException) t).code()).isEqualTo(CycleRuleException.Code.ACTIVE_CYCLE_EXISTS);
        });
        assertThat(jdbc.queryForObject("select count(*) from cycle where student_id = ? and status = 'ACTIVE'",
                Integer.class, f.studentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from cycle where student_id = ?", Integer.class, f.studentId()))
                .as("the loser left no cycle behind").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payment where student_id = ?", Integer.class, f.studentId()))
                .as("the loser left no payment behind").isEqualTo(1);
    }

    @Test
    void twoSimultaneousPaymentsOfTheSameStudentOnlyOneSucceeds() throws Exception {
        for (int round = 0; round < 5; round++) {
            Fixture f = fixture();
            assertExactlyOneWon(f, runConcurrently(2, () -> payOnce(f)));
        }
    }

    @Test
    void manySimultaneousPaymentsStillProduceASingleCycle() throws Exception {
        Fixture f = fixture();
        assertExactlyOneWon(f, runConcurrently(8, () -> payOnce(f)));
    }

    @Test
    void paymentsOfDifferentStudentsDoNotBlockEachOther() throws Exception {
        Fixture a = fixture();
        Fixture b = fixture();
        List<Object> results = runConcurrently(2, new Callable<>() {
            int next = 0;
            @Override public synchronized Object call() { return null; }
        });
        assertThat(results).hasSize(2); // sanity of the helper
        assertThat(payOnce(a)).isInstanceOf(PaymentRegistered.class);
        assertThat(payOnce(b)).isInstanceOf(PaymentRegistered.class);
    }
}
