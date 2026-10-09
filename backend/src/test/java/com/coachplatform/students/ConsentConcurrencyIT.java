package com.coachplatform.students;

import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.auth.AppUserRepository;
import com.coachplatform.auth.AuthDtos.RegisterCoachRequest;
import com.coachplatform.auth.AuthService;
import com.coachplatform.students.api.ConsentType;
import com.coachplatform.students.api.StudentInput;
import com.coachplatform.students.domain.StudentRuleException;
import com.coachplatform.support.ConsentFixtures;
import com.coachplatform.support.PostgresIntegrationTest;
import com.coachplatform.tenant.TenantContext;
import java.time.LocalDate;
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
 * Two simultaneous changes to the same consent are serialized by the student's row lock: only one passes the "already in
 * force" / "nothing to revoke" check, so the history never holds a duplicate acceptance or a double revocation.
 */
class ConsentConcurrencyIT extends PostgresIntegrationTest {

    @Autowired AuthService auth;
    @Autowired AppUserRepository users;
    @Autowired StudentService students;
    @Autowired InvitationService invitations;
    @Autowired ConsentService consents;
    @Autowired JdbcTemplate jdbc;

    private record Ctx(UUID coachId, UUID studentId, UUID studentUser) {
    }

    private Ctx studentWithWhatsapp() {
        String coachEmail = "coach-" + UUID.randomUUID() + "@test.co";
        UUID coachId = auth.registerCoach(new RegisterCoachRequest("Coach", coachEmail, "Prueba-1234-x")).coachId();
        UUID coachUser = users.findByEmailIgnoreCase(coachEmail).orElseThrow().getId();
        var created = TenantContext.callAs(coachId, () -> students.create(new StudentInput("Ana", "alumno-" + UUID.randomUUID() + "@test.co", null,
                LocalDate.of(1990, 5, 1), null, null), coachUser));
        invitations.accept(created.invitation().token(), "Primera-clave-1", true, ConsentFixtures.version("DATA_ADULT"), true, ConsentFixtures.version("WHATSAPP"));
        UUID userId = jdbc.queryForObject("select user_id from student where id = ?", UUID.class, created.student().id());
        return new Ctx(coachId, created.student().id(), userId);
    }

    private List<Object> race(Ctx c, Callable<Object> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
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

    @Test
    void onlyOneOfTwoSimultaneousRevocationsSucceeds() throws Exception {
        for (int round = 0; round < 5; round++) {
            Ctx c = studentWithWhatsapp();
            List<Object> results = race(c, () -> consents.revokeAsStudent(c.studentUser(), ConsentType.WHATSAPP));
            assertThat(results.stream().filter(r -> !(r instanceof Throwable))).hasSize(1);
            assertThat(results.stream().filter(r -> r instanceof StudentRuleException e && e.code() == StudentRuleException.Code.CONSENT_NOT_ACTIVE)).hasSize(1);
            assertThat(jdbc.queryForObject("select count(*) from consent_revocation where student_id = ?", Integer.class, c.studentId())).isEqualTo(1);
        }
    }

    @Test
    void onlyOneOfTwoSimultaneousAcceptancesSucceeds() throws Exception {
        for (int round = 0; round < 5; round++) {
            Ctx c = studentWithWhatsapp();
            TenantContext.runAs(c.coachId(), () -> consents.revokeAsStudent(c.studentUser(), ConsentType.WHATSAPP));
            List<Object> results = race(c, () -> consents.acceptAsStudent(c.studentUser(), ConsentType.WHATSAPP, ConsentFixtures.version("WHATSAPP")));
            assertThat(results.stream().filter(r -> !(r instanceof Throwable))).hasSize(1);
            assertThat(results.stream().filter(r -> r instanceof StudentRuleException e && e.code() == StudentRuleException.Code.CONSENT_ALREADY_ACTIVE)).hasSize(1);
            assertThat(jdbc.queryForObject("select count(*) from consent_record where student_id = ? and type = 'WHATSAPP'", Integer.class, c.studentId()))
                    .as("the initial acceptance plus exactly one more").isEqualTo(2);
        }
    }

    @Test
    void revokingTheLastDataConsentWhileWhatsappIsAcceptedLeavesOneConsistentState() throws Exception {
        Ctx c = studentWithWhatsapp();
        List<Object> results = race(c, () -> consents.revokeAsStudent(c.studentUser(), ConsentType.DATA_ADULT));
        assertThat(results.stream().filter(r -> !(r instanceof Throwable))).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from consent_revocation where student_id = ? and type = 'DATA_ADULT'", Integer.class, c.studentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select active from app_user where id = ?", Boolean.class, c.studentUser())).isFalse();
        assertThat(jdbc.queryForObject("select anonymization_requested_at is not null from student where id = ?", Boolean.class, c.studentId())).isTrue();
    }
}
