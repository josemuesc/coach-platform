package com.coachplatform.students;

import com.coachplatform.tenant.CrossTenantAccess;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Cross-tenant by necessity: the public reset endpoint has no logged-in user, so the tenant is derived from the hash of the link's
 * token. Reads one column and nothing else; the caller then runs as that tenant. Unknown, used, revoked and expired links all give
 * the same empty answer.
 */
@Component
@CrossTenantAccess
class PasswordResetTenantLookup {

    private final JdbcClient jdbc;

    PasswordResetTenantLookup(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    Optional<UUID> coachIdIfUsable(String tokenHash, Instant now) {
        return jdbc.sql("select coach_id from password_reset where token_hash = :hash and used_at is null "
                        + "and revoked_at is null and expires_at > :now")
                .param("hash", tokenHash)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .query(UUID.class)
                .optional();
    }
}
