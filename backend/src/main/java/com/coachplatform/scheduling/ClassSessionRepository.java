package com.coachplatform.scheduling;

import com.coachplatform.scheduling.api.EventStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ClassSessionRepository extends JpaRepository<ClassSession, UUID> {

    /**
     * Only the ids of the SCHEDULED events overlapping [from, to), with NO entity loaded: a booking reads this first, takes
     * the locks it needs and only then loads the events, so it never works on a stale cached copy.
     */
    @Query("select s.id from ClassSession s where s.status = com.coachplatform.scheduling.api.EventStatus.SCHEDULED "
            + "and s.startsAt < :to and s.endsAt > :from order by s.id")
    List<UUID> findScheduledOverlappingIds(@Param("from") Instant from, @Param("to") Instant to);

    /** SELECT ... FOR UPDATE on one event row: adding or removing a place in it is serialized. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ClassSession s where s.id = :id")
    Optional<ClassSession> findByIdForUpdate(@Param("id") UUID id);

    @Query("select s from ClassSession s where s.status = com.coachplatform.scheduling.api.EventStatus.SCHEDULED "
            + "and s.startsAt < :to and s.endsAt > :from order by s.startsAt")
    List<ClassSession> findScheduledOverlapping(@Param("from") Instant from, @Param("to") Instant to);

    List<ClassSession> findByStatusAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAt(EventStatus status, Instant from, Instant to);
}
