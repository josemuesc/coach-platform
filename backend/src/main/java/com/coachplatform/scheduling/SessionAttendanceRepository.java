package com.coachplatform.scheduling;

import com.coachplatform.scheduling.api.AttendanceStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SessionAttendanceRepository extends JpaRepository<SessionAttendance, UUID> {

    String LIVE = "(com.coachplatform.scheduling.api.AttendanceStatus.SCHEDULED, "
            + "com.coachplatform.scheduling.api.AttendanceStatus.ATTENDED, com.coachplatform.scheduling.api.AttendanceStatus.NO_SHOW)";

    /** The owner's id and the event's id, with NO entity loaded, so the student can be locked before the place is read. */
    @Query("select a.studentId, a.sessionId from SessionAttendance a where a.id = :id")
    List<Object[]> findOwnerAndSessionById(@Param("id") UUID id);

    @Query("select distinct a.studentId from SessionAttendance a where a.sessionId = :sessionId and a.status in " + LIVE)
    List<UUID> findLiveStudentIdsBySession(@Param("sessionId") UUID sessionId);

    @Query("select count(a) from SessionAttendance a where a.sessionId = :sessionId and a.status in " + LIVE)
    int countLive(@Param("sessionId") UUID sessionId);

    /** (event id, live places) for several events at once. */
    @Query("select a.sessionId, count(a) from SessionAttendance a where a.sessionId in :ids and a.status in " + LIVE + " group by a.sessionId")
    List<Object[]> countLiveBySessions(@Param("ids") Collection<UUID> ids);

    @Query("select count(a) > 0 from SessionAttendance a where a.sessionId = :sessionId and a.studentId = :studentId and a.status in " + LIVE)
    boolean existsLive(@Param("sessionId") UUID sessionId, @Param("studentId") UUID studentId);

    @Query("select a from SessionAttendance a where a.sessionId in :ids and a.status in " + LIVE + " order by a.createdAt")
    List<SessionAttendance> findLiveBySessions(@Param("ids") Collection<UUID> ids);

    /** The student's live place in an event (at most one: a unique index guarantees it). */
    @Query("select a from SessionAttendance a where a.sessionId = :sessionId and a.studentId = :studentId and a.status in " + LIVE)
    Optional<SessionAttendance> findLiveBySessionAndStudent(@Param("sessionId") UUID sessionId, @Param("studentId") UUID studentId);

    List<SessionAttendance> findBySessionId(UUID sessionId);

    List<SessionAttendance> findByStudentIdOrderByCreatedAtDesc(UUID studentId);

    int countByCycleIdAndStatus(UUID cycleId, AttendanceStatus status);

    /** SCHEDULED places whose event already started and are still unmarked. */
    @Query("select a from SessionAttendance a, ClassSession s where a.sessionId = s.id "
            + "and a.status = com.coachplatform.scheduling.api.AttendanceStatus.SCHEDULED and s.startsAt <= :now order by s.startsAt")
    List<SessionAttendance> findPending(@Param("now") Instant now);

    @Query("select a from SessionAttendance a, ClassSession s where a.sessionId = s.id and a.cycleId = :cycleId "
            + "and a.status = com.coachplatform.scheduling.api.AttendanceStatus.SCHEDULED and s.startsAt <= :now order by s.startsAt")
    List<SessionAttendance> findPendingByCycle(@Param("cycleId") UUID cycleId, @Param("now") Instant now);

    @Query("select count(a) from SessionAttendance a, ClassSession s where a.sessionId = s.id and a.cycleId = :cycleId "
            + "and a.status = com.coachplatform.scheduling.api.AttendanceStatus.SCHEDULED and s.startsAt <= :now")
    int countPendingByCycle(@Param("cycleId") UUID cycleId, @Param("now") Instant now);

    /** SCHEDULED places whose event has not started yet. */
    @Query("select a from SessionAttendance a, ClassSession s where a.sessionId = s.id and a.cycleId = :cycleId "
            + "and a.status = com.coachplatform.scheduling.api.AttendanceStatus.SCHEDULED and s.startsAt > :now order by s.startsAt")
    List<SessionAttendance> findFutureByCycle(@Param("cycleId") UUID cycleId, @Param("now") Instant now);

    Optional<SessionAttendance> findFirstBySessionIdAndStudentIdAndStatus(UUID sessionId, UUID studentId, AttendanceStatus status);
}
