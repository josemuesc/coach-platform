package com.coachplatform.students;

import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.auth.AppUserRepository;
import com.coachplatform.auth.AuthDtos.RegisterCoachRequest;
import com.coachplatform.auth.AuthService;
import com.coachplatform.students.api.PasswordResetIssued;
import com.coachplatform.students.api.StudentInput;
import com.coachplatform.support.ConsentFixtures;
import com.coachplatform.support.PostgresIntegrationTest;
import com.coachplatform.tenant.TenantContext;
import java.time.LocalDate;
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

/**
 * V7 on real PostgreSQL: the one-time reset link works exactly once even when two requests arrive together, and the schema itself
 * refuses the combinations no flow produces (password change shape, two open links, audit shape, mutating the audit) and has RLS on.
 */
class PasswordRecoveryIT extends PostgresIntegrationTest {

    private static final String CHECK = "23514";
    private static final String UNIQUE = "23505";
    private static final String IMMUTABLE = "23000";

    @Autowired AuthService auth;
    @Autowired AppUserRepository users;
    @Autowired StudentService students;
    @Autowired InvitationService invitations;
    @Autowired PasswordResetService resets;
    @Autowired JdbcTemplate jdbc;

    private record Ctx(UUID coachId, UUID coachUser, UUID studentId, UUID studentUser) {
    }

    private Ctx studentWithAccount() {
        String coachEmail = "coach-" + UUID.randomUUID() + "@test.co";
        UUID coachId = auth.registerCoach(new RegisterCoachRequest("Coach", coachEmail, "Prueba-1234-x")).coachId();
        UUID coachUser = users.findByEmailIgnoreCase(coachEmail).orElseThrow().getId();
        var created = TenantContext.callAs(coachId, () -> students.create(new StudentInput("Ana", "alumno-" + UUID.randomUUID() + "@test.co", null,
                LocalDate.of(1990, 5, 1), null, null), coachUser));
        invitations.accept(created.invitation().token(), "Primera-clave-1", true, ConsentFixtures.version("DATA_ADULT"), false, null);
        UUID userId = jdbc.queryForObject("select user_id from student where id = ?", UUID.class, created.student().id());
        return new Ctx(coachId, coachUser, created.student().id(), userId);
    }

    private PasswordResetIssued issue(Ctx c) {
        return TenantContext.callAs(c.coachId(), () -> resets.issue(c.studentId(), c.coachUser()));
    }

    private static String sqlState(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            Throwable t = e;
            while (t.getCause() != null) {
                t = t.getCause();
            }
            if (t instanceof java.sql.SQLException sql) {
                return sql.getSQLState();
            }
            throw e;
        }
        return null;
    }

    // ---- concurrency ---------------------------------------------------------------------------------------

    @Test
    void twoSimultaneousRedemptionsOfOneLinkSucceedExactlyOnce() throws Exception {
        Ctx c = studentWithAccount();
        String token = issue(c).token();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String password = "Clave-nueva-" + i + "-xx";
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    resets.redeem(token, password);
                    return true;
                } catch (InvalidResetLinkException e) {
                    return false;
                }
            }));
        }
        ready.await();
        go.countDown();
        int succeeded = 0;
        for (Future<Boolean> f : results) {
            succeeded += f.get(30, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from account_audit where event = 'RESET_LINK_USED' and target_user_id = ?",
                Integer.class, c.studentUser())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select password_change_method from app_user where id = ?", String.class, c.studentUser()))
                .isEqualTo("COACH_LINK");
    }

    @Test
    void anExpiredLinkCannotBeRedeemedButStillOccupiesTheOpenSlotUntilReplaced() throws Exception {
        Ctx c = studentWithAccount();
        String expiredToken = InvitationToken.generate();
        jdbc.update("insert into password_reset (coach_id, student_id, user_id, token_hash, created_by_user_id, created_at, expires_at) "
                + "values (?, ?, ?, ?, ?, now() - interval '2 days', now() - interval '1 day')", c.coachId(), c.studentId(), c.studentUser(),
                InvitationToken.hash(expiredToken), c.coachUser());
        org.junit.jupiter.api.Assertions.assertThrows(InvalidResetLinkException.class, () -> resets.redeem(expiredToken, "Clave-nueva-1-xx"));
        PasswordResetIssued second = issue(c);   // replacing the expired one must not trip the one-open-link index
        resets.redeem(second.token(), "Clave-nueva-2-xx");
        assertThat(jdbc.queryForObject("select count(*) from password_reset where user_id = ? and revoked_at is not null",
                Integer.class, c.studentUser())).isEqualTo(1);
    }

    // ---- schema --------------------------------------------------------------------------------------------

    @Test
    void thePasswordChangeColumnsMustAgree() {
        Ctx c = studentWithAccount();
        assertThat(sqlState(() -> jdbc.update("update app_user set password_changed_at = now() where id = ?", c.studentUser()))).isEqualTo(CHECK);
        assertThat(sqlState(() -> jdbc.update("update app_user set password_change_method = 'SELF' where id = ?", c.studentUser()))).isEqualTo(CHECK);
        assertThat(sqlState(() -> jdbc.update("update app_user set password_changed_at = now(), password_change_method = 'OTHER' where id = ?",
                c.studentUser()))).isEqualTo(CHECK);
        assertThat(sqlState(() -> jdbc.update("update app_user set password_changed_at = now(), password_change_method = 'SELF' where id = ?",
                c.studentUser()))).isNull();
    }

    @Test
    void aLoginHasAtMostOneOpenLinkAndALinkEndsOnlyOneWay() {
        Ctx c = studentWithAccount();
        String insert = "insert into password_reset (coach_id, student_id, user_id, token_hash, created_by_user_id, created_at, expires_at%s) "
                + "values (?, ?, ?, ?, ?, now(), now() + interval '1 day'%s)";
        Runnable open = () -> jdbc.update(String.format(insert, "", ""), c.coachId(), c.studentId(), c.studentUser(),
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""), c.coachUser());
        assertThat(sqlState(open)).isNull();
        assertThat(sqlState(open)).isEqualTo(UNIQUE);
        assertThat(sqlState(() -> jdbc.update("update password_reset set used_at = now(), revoked_at = now() where user_id = ?",
                c.studentUser()))).isEqualTo(CHECK);
        assertThat(sqlState(() -> jdbc.update("insert into password_reset (coach_id, student_id, user_id, token_hash, created_by_user_id, "
                + "created_at, expires_at) values (?, ?, ?, ?, ?, now(), now())", c.coachId(), c.studentId(), c.studentUser(),
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""), c.coachUser()))).isEqualTo(CHECK);
    }

    @Test
    void theAccountAuditRefusesImpossibleCombinationsAndCannotBeChanged() {
        Ctx c = studentWithAccount();
        PasswordResetIssued issued = issue(c);
        UUID resetId = jdbc.queryForObject("select id from password_reset where user_id = ?", UUID.class, c.studentUser());
        String sql = "insert into account_audit (coach_id, event, target_user_id, actor_user_id, student_id, reset_id) values (?, ?, ?, ?, ?, ?)";

        // PASSWORD_CHANGED is the account acting on itself, with no link
        assertThat(sqlState(() -> jdbc.update(sql, c.coachId(), "PASSWORD_CHANGED", c.studentUser(), c.coachUser(), null, null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> jdbc.update(sql, c.coachId(), "PASSWORD_CHANGED", c.studentUser(), c.studentUser(), c.studentId(), resetId))).isEqualTo(CHECK);
        // a link is created / revoked BY somebody else, and used by the account itself
        assertThat(sqlState(() -> jdbc.update(sql, c.coachId(), "RESET_LINK_CREATED", c.studentUser(), c.studentUser(), c.studentId(), resetId))).isEqualTo(CHECK);
        assertThat(sqlState(() -> jdbc.update(sql, c.coachId(), "RESET_LINK_USED", c.studentUser(), c.coachUser(), c.studentId(), resetId))).isEqualTo(CHECK);
        assertThat(sqlState(() -> jdbc.update(sql, c.coachId(), "RESET_LINK_CREATED", c.studentUser(), c.coachUser(), c.studentId(), null))).isEqualTo(CHECK);
        assertThat(sqlState(() -> jdbc.update(sql, c.coachId(), "SOMETHING_ELSE", c.studentUser(), c.coachUser(), c.studentId(), resetId))).isEqualTo(CHECK);

        assertThat(sqlState(() -> jdbc.update("update account_audit set event = 'RESET_LINK_USED' where target_user_id = ?", c.studentUser()))).isEqualTo(IMMUTABLE);
        assertThat(sqlState(() -> jdbc.update("delete from account_audit where target_user_id = ?", c.studentUser()))).isEqualTo(IMMUTABLE);
        assertThat(sqlState(() -> jdbc.execute("truncate account_audit"))).isEqualTo(IMMUTABLE);
        assertThat(issued.token()).isNotBlank();
    }

    private static final String FK = "23503";

    private String hex64() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private UUID insertLink(Ctx c, String hash) {
        return jdbc.queryForObject("insert into password_reset (coach_id, student_id, user_id, token_hash, created_by_user_id, created_at, "
                + "expires_at) values (?, ?, ?, ?, ?, now(), now() + interval '1 day') returning id", UUID.class,
                c.coachId(), c.studentId(), c.studentUser(), hash, c.coachUser());
    }

    @Test
    void aLinkCanOnlyBeUsedOrRevokedOnceAndNothingElseOfItEverChanges() {
        Ctx c = studentWithAccount();
        UUID id = insertLink(c, hex64());

        // nothing but used_at / revoked_at is writable
        assertThat(sqlState(() -> jdbc.update("update password_reset set token_hash = ? where id = ?", hex64(), id))).isEqualTo(IMMUTABLE);
        assertThat(sqlState(() -> jdbc.update("update password_reset set expires_at = expires_at + interval '10 days' where id = ?", id))).isEqualTo(IMMUTABLE);
        assertThat(sqlState(() -> jdbc.update("update password_reset set created_at = created_at - interval '1 day' where id = ?", id))).isEqualTo(IMMUTABLE);
        assertThat(sqlState(() -> jdbc.update("update password_reset set created_by_user_id = ? where id = ?", c.studentUser(), id))).isEqualTo(IMMUTABLE);
        assertThat(sqlState(() -> jdbc.update("update password_reset set user_id = ? where id = ?", c.coachUser(), id))).isEqualTo(IMMUTABLE);

        // NULL -> value is the one allowed move
        assertThat(sqlState(() -> jdbc.update("update password_reset set used_at = now() where id = ?", id))).isNull();
        // ...and then it is frozen: no reviving, no moving the end, no revoking a used link
        assertThat(sqlState(() -> jdbc.update("update password_reset set used_at = null where id = ?", id))).isEqualTo(IMMUTABLE);
        assertThat(sqlState(() -> jdbc.update("update password_reset set used_at = now() + interval '1 hour' where id = ?", id))).isEqualTo(IMMUTABLE);
        assertThat(sqlState(() -> jdbc.update("update password_reset set revoked_at = now() where id = ?", id))).isEqualTo(CHECK);

        UUID other = jdbc.queryForObject("select id from password_reset where id = ?", UUID.class, id);
        assertThat(other).isEqualTo(id);
        assertThat(sqlState(() -> jdbc.update("delete from password_reset where id = ?", id))).isEqualTo(IMMUTABLE);
        assertThat(sqlState(() -> jdbc.execute("truncate password_reset cascade"))).isEqualTo(IMMUTABLE);
    }

    @Test
    void aRevokedLinkCannotBeRevivedEither() {
        Ctx c = studentWithAccount();
        UUID id = insertLink(c, hex64());
        assertThat(sqlState(() -> jdbc.update("update password_reset set revoked_at = now() where id = ?", id))).isNull();
        assertThat(sqlState(() -> jdbc.update("update password_reset set revoked_at = null where id = ?", id))).isEqualTo(IMMUTABLE);
        assertThat(sqlState(() -> jdbc.update("update password_reset set used_at = now() where id = ?", id))).isEqualTo(CHECK);
    }

    @Test
    void theTokenHashMustBeSixtyFourLowercaseHexCharacters() {
        Ctx c = studentWithAccount();
        for (String bad : List.of("short", "A".repeat(64), "g".repeat(64), hex64().toUpperCase(), hex64() + "0")) {
            assertThat(sqlState(() -> insertLink(c, bad))).as(bad).isNotNull();
        }
        assertThat(sqlState(() -> insertLink(c, "A".repeat(64)))).isEqualTo(CHECK);
        assertThat(sqlState(() -> insertLink(c, hex64()))).isNull();
    }

    @Test
    void aLinkOrAnAuditLineCannotReferenceAnotherCoachsAccount() {
        Ctx mine = studentWithAccount();
        Ctx theirs = studentWithAccount();

        // the login of the link, or its creator, belongs to a different coach than the row
        assertThat(sqlState(() -> jdbc.update("insert into password_reset (coach_id, student_id, user_id, token_hash, created_by_user_id, expires_at) "
                + "values (?, ?, ?, ?, ?, now() + interval '1 day')", mine.coachId(), mine.studentId(), theirs.studentUser(), hex64(), mine.coachUser())))
                .isEqualTo(FK);
        assertThat(sqlState(() -> jdbc.update("insert into password_reset (coach_id, student_id, user_id, token_hash, created_by_user_id, expires_at) "
                + "values (?, ?, ?, ?, ?, now() + interval '1 day')", mine.coachId(), mine.studentId(), mine.studentUser(), hex64(), theirs.coachUser())))
                .isEqualTo(FK);
        assertThat(sqlState(() -> jdbc.update("insert into password_reset (coach_id, student_id, user_id, token_hash, created_by_user_id, expires_at) "
                + "values (?, ?, ?, ?, ?, now() + interval '1 day')", mine.coachId(), theirs.studentId(), mine.studentUser(), hex64(), mine.coachUser())))
                .isEqualTo(FK);

        UUID resetId = insertLink(mine, hex64());
        String sql = "insert into account_audit (coach_id, event, target_user_id, actor_user_id, student_id, reset_id) values (?, ?, ?, ?, ?, ?)";
        assertThat(sqlState(() -> jdbc.update(sql, mine.coachId(), "RESET_LINK_CREATED", theirs.studentUser(), mine.coachUser(), mine.studentId(), resetId)))
                .isEqualTo(FK);
        assertThat(sqlState(() -> jdbc.update(sql, mine.coachId(), "RESET_LINK_CREATED", mine.studentUser(), theirs.coachUser(), mine.studentId(), resetId)))
                .isEqualTo(FK);
        assertThat(sqlState(() -> jdbc.update(sql, mine.coachId(), "RESET_LINK_CREATED", mine.studentUser(), mine.coachUser(), mine.studentId(), resetId)))
                .isNull();
        // another coach's link cannot be cited by my audit line either
        UUID theirLink = insertLink(theirs, hex64());
        assertThat(sqlState(() -> jdbc.update(sql, mine.coachId(), "RESET_LINK_REVOKED", mine.studentUser(), mine.coachUser(), mine.studentId(), theirLink)))
                .isEqualTo(FK);
    }

    @Test
    void twoSimultaneousLinkRequestsForTheSameStudentBothSucceedAndLeaveOneOpenLink() throws Exception {
        Ctx c = studentWithAccount();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PasswordResetIssued>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return issue(c);
            }));
        }
        ready.await();
        go.countDown();
        List<PasswordResetIssued> issued = new ArrayList<>();
        for (Future<PasswordResetIssued> f : results) {
            issued.add(f.get(30, TimeUnit.SECONDS));   // an exception here (a 500) would fail the test
        }
        pool.shutdown();

        assertThat(jdbc.queryForObject("select count(*) from password_reset where user_id = ? and used_at is null and revoked_at is null",
                Integer.class, c.studentUser())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from password_reset where user_id = ? and revoked_at is not null",
                Integer.class, c.studentUser())).isEqualTo(1);
        assertThat(jdbc.queryForList("select event from account_audit where target_user_id = ?", String.class, c.studentUser()))
                .containsExactlyInAnyOrder("RESET_LINK_CREATED", "RESET_LINK_CREATED", "RESET_LINK_REVOKED");
        // exactly one of the two tokens still works
        int works = 0;
        for (PasswordResetIssued i : issued) {
            try {
                resets.redeem(i.token(), "Clave-nueva-" + works + "-xx");
                works++;
            } catch (InvalidResetLinkException expected) {
                // the replaced one
            }
        }
        assertThat(works).isEqualTo(1);
    }

    @Test
    void rowLevelSecurityIsOnInTheNewTables() {
        for (String table : List.of("password_reset", "account_audit")) {
            assertThat(jdbc.queryForObject("select relrowsecurity from pg_class where relname = ?", Boolean.class, table)).as(table).isTrue();
        }
    }
}
