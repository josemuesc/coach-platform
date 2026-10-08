package com.coachplatform.students;

import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.auth.AppUserRepository;
import com.coachplatform.auth.AuthDtos.RegisterCoachRequest;
import com.coachplatform.auth.AuthService;
import com.coachplatform.students.api.InvitationAccepted;
import com.coachplatform.students.api.StudentInput;
import com.coachplatform.support.PostgresIntegrationTest;
import com.coachplatform.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** An invitation is single-use even when two accepts arrive at the same instant. */
class InvitationConcurrencyIT extends PostgresIntegrationTest {

    @Autowired AuthService auth;
    @Autowired AppUserRepository users;
    @Autowired StudentService students;
    @Autowired InvitationService invitations;
    @Autowired JdbcTemplate jdbc;

    @Test
    void onlyOneOfTwoSimultaneousAcceptsSucceeds() throws Exception {
        for (int round = 0; round < 5; round++) {
            String coachEmail = "coach-" + UUID.randomUUID() + "@test.co";
            UUID coachId = auth.registerCoach(new RegisterCoachRequest("Coach", coachEmail, "Prueba-1234-x")).coachId();
            UUID coachUser = users.findByEmailIgnoreCase(coachEmail).orElseThrow().getId();
            String studentEmail = "alumno-" + UUID.randomUUID() + "@test.co";
            String token = TenantContext.callAs(coachId,
                    () -> students.create(new StudentInput("Ana", studentEmail, null), coachUser).invitation().token());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (String password : List.of("Primera-clave-1", "Segunda-clave-2")) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return invitations.accept(token, password);
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

            assertThat(results.stream().filter(r -> r instanceof InvitationAccepted)).hasSize(1);
            assertThat(results.stream().filter(r -> r instanceof InvalidInvitationException)).hasSize(1);
            assertThat(jdbc.queryForObject("select count(*) from app_user where lower(email) = ?", Integer.class, studentEmail))
                    .as("exactly one account was created").isEqualTo(1);
        }
    }
}
