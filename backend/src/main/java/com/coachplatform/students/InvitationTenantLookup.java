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
 * Cross-tenant by necessity: the public accept/preview endpoints have no logged-in user, so the tenant must be
 * derived from the token's hash. Reads one column and nothing else; the caller then runs as that tenant.
 * Never reveals why a token is not accepted.
 */
@Component
@CrossTenantAccess
class InvitationTenantLookup {

    private final JdbcClient jdbc;

    InvitationTenantLookup(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * The coach of an invitation, but ONLY if the invitation is still usable (not used, not revoked, not expired).
     * An unknown, used, revoked or expired token all produce the same empty answer, so the caller cannot tell them
     * apart. Returns one id and nothing else.
     */
    Optional<UUID> coachIdIfUsable(String tokenHash, Instant now) {
        return jdbc.sql("select coach_id from invitation where token_hash = :hash and used_at is null "
                        + "and revoked_at is null and expires_at > :now")
                .param("hash", tokenHash)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .query(UUID.class)
                .optional();
    }
}
