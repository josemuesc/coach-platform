package com.coachplatform.students;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PasswordResetRepository extends JpaRepository<PasswordReset, UUID> {

    Optional<PasswordReset> findByTokenHash(String tokenHash);

    /** Links not used and not revoked (an expired one counts: it still occupies the "one open link per login" slot). */
    @Query("select r from PasswordReset r where r.userId = :userId and r.usedAt is null and r.revokedAt is null")
    List<PasswordReset> findOpenByUserId(@Param("userId") UUID userId);

    /** Single use in ONE atomic statement: of two simultaneous resets only one updates a row. 1 = consumed, 0 = not usable. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PasswordReset r set r.usedAt = :now where r.tokenHash = :hash "
            + "and r.usedAt is null and r.revokedAt is null and r.expiresAt > :now")
    int consume(@Param("hash") String tokenHash, @Param("now") Instant now);
}
