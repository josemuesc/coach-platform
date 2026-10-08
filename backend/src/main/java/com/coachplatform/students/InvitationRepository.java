package com.coachplatform.students;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    Optional<Invitation> findByTokenHash(String tokenHash);

    /**
     * Single-use consumption in ONE atomic statement: of two simultaneous accepts only one updates a row.
     * Returns 1 if the invitation was usable and is now used, 0 otherwise (unknown, used, revoked or expired).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Invitation i set i.usedAt = :now where i.tokenHash = :hash "
            + "and i.usedAt is null and i.revokedAt is null and i.expiresAt > :now")
    int consume(@Param("hash") String tokenHash, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Invitation i set i.revokedAt = :now where i.studentId = :studentId "
            + "and i.usedAt is null and i.revokedAt is null")
    int revokeOpenFor(@Param("studentId") UUID studentId, @Param("now") Instant now);
}
