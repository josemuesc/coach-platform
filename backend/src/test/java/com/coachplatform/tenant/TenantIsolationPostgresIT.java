package com.coachplatform.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coachplatform.CoachPlatformApplication;
import com.coachplatform.auth.AppUser;
import com.coachplatform.auth.AppUserRepository;
import com.coachplatform.coach.CoachService;
import com.coachplatform.security.UserRole;
import com.coachplatform.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import testfixtures.postgres.PostgresTenantFixtures;
import testfixtures.postgres.TenantScopedUser;
import testfixtures.postgres.TenantScopedUserRepository;

/**
 * Tenant isolation against REAL PostgreSQL and the REAL app_user table (Flyway V1, Hibernate in validate mode),
 * so we do not depend only on H2.
 */
@SpringBootTest(classes = {CoachPlatformApplication.class, PostgresTenantFixtures.class})
class TenantIsolationPostgresIT extends PostgresIntegrationTest {

    @Autowired CoachService coaches;
    @Autowired AppUserRepository users;
    @Autowired TenantScopedUserRepository scopedUsers;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void tenantOnlySeesItsOwnRowsInTheRealTable() {
        UUID coachA = coaches.createCoach("Coach A");
        UUID coachB = coaches.createCoach("Coach B");
        String emailA = uniqueEmail();
        String emailB = uniqueEmail();
        // Inserted through the unfiltered production mapping, so both rows really exist in Postgres.
        UUID userA = users.save(new AppUser(coachA, emailA, "hash", UserRole.COACH)).getId();
        UUID userB = users.save(new AppUser(coachB, emailB, "hash", UserRole.COACH)).getId();

        TenantContext.set(coachA);
        assertThat(scopedUsers.findAll()).extracting(TenantScopedUser::getEmail).containsExactly(emailA);
        assertThat(scopedUsers.findById(userA)).isPresent();
        assertThat(scopedUsers.findById(userB)).isEmpty();

        TenantContext.set(coachB);
        assertThat(scopedUsers.findAll()).extracting(TenantScopedUser::getEmail).containsExactly(emailB);
        assertThat(scopedUsers.findById(userA)).isEmpty();

        TenantContext.clear();
        assertThat(scopedUsers.findAll()).isEmpty();
    }

    @Test
    void insertsAreStampedWithTheCurrentTenant() {
        UUID coachA = coaches.createCoach("Coach A");
        TenantContext.set(coachA);

        UUID id = scopedUsers.save(new TenantScopedUser(uniqueEmail())).getId();

        assertThat(users.findById(id).orElseThrow().getCoachId()).isEqualTo(coachA);
    }

    @Test
    void insertWithoutAnExistingTenantFailsOnTheForeignKey() {
        // No tenant at all: sentinel id matches no coach.
        assertThatThrownBy(() -> scopedUsers.saveAndFlush(new TenantScopedUser(uniqueEmail())))
                .isInstanceOf(DataIntegrityViolationException.class);

        // A tenant id that is not a real coach.
        TenantContext.set(UUID.randomUUID());
        assertThatThrownBy(() -> scopedUsers.saveAndFlush(new TenantScopedUser(uniqueEmail())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@test.co";
    }
}
