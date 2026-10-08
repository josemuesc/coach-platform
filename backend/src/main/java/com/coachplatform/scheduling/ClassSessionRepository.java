package com.coachplatform.scheduling;

import com.coachplatform.scheduling.api.SessionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ClassSessionRepository extends JpaRepository<ClassSession, UUID> {

    /** Any class (any status) that starts inside [from, to): the coach's agenda. */
    List<ClassSession> findByStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAt(Instant from, Instant to);

    /** SCHEDULED classes whose time range overlaps [from, to): what blocks a new class or a block. */
    @Query("select s from ClassSession s where s.status = com.coachplatform.scheduling.api.SessionStatus.SCHEDULED "
            + "and s.startsAt < :to and s.endsAt > :from order by s.startsAt")
    List<ClassSession> findScheduledOverlapping(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Only the owner's id, with NO entity loaded: lets a service take the student's row lock BEFORE it reads the class,
     * so the class is read fresh from the database under the lock (a cached copy could be stale).
     */
    @Query("select s.studentId from ClassSession s where s.id = :id")
    java.util.Optional<UUID> findStudentIdById(@Param("id") UUID id);

    List<ClassSession> findByStudentIdOrderByStartsAtDesc(UUID studentId);

    int countByCycleIdAndStatus(UUID cycleId, SessionStatus status);

    /** SCHEDULED classes that already started and are still unmarked. */
    List<ClassSession> findByStatusAndStartsAtLessThanEqualOrderByStartsAt(SessionStatus status, Instant now);

    List<ClassSession> findByCycleIdAndStatusAndStartsAtLessThanEqualOrderByStartsAt(UUID cycleId, SessionStatus status, Instant now);

    int countByCycleIdAndStatusAndStartsAtLessThanEqual(UUID cycleId, SessionStatus status, Instant now);

    List<ClassSession> findByCycleIdAndStatusAndStartsAtGreaterThan(UUID cycleId, SessionStatus status, Instant now);
}
