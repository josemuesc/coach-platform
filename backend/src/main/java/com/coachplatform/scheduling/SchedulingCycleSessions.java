package com.coachplatform.scheduling;

import com.coachplatform.billing.api.CycleSessions;
import com.coachplatform.billing.api.FutureAttendance;
import com.coachplatform.billing.api.Modality;
import com.coachplatform.billing.api.PendingSession;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the one internal port ({@link CycleSessions}) so billing can ask about the ATTENDANCES of a cycle without
 * depending on this module. It counts attendances, never events. The student lock is already held by the caller.
 */
@Component
class SchedulingCycleSessions implements CycleSessions {

    private final SessionAttendanceRepository attendances;
    private final ClassSessionRepository events;
    private final Clock clock;

    SchedulingCycleSessions(SessionAttendanceRepository attendances, ClassSessionRepository events, Clock clock) {
        this.attendances = attendances;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<PendingSession> pendingMarks(UUID cycleId) {
        var pending = attendances.findPendingByCycle(cycleId, clock.instant());
        Map<UUID, ClassSession> byId = eventsOf(pending);
        return pending.stream().map(a -> {
            ClassSession e = byId.get(a.getSessionId());
            return new PendingSession(a.getId(), e.getId(), a.getStudentId(), e.getStartsAt(), e.getEndsAt());
        }).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public int pendingMarkCount(UUID cycleId) {
        return attendances.countPendingByCycle(cycleId, clock.instant());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<FutureAttendance> futureAttendances(UUID cycleId) {
        var future = attendances.findFutureByCycle(cycleId, clock.instant());
        Map<UUID, ClassSession> byId = eventsOf(future);
        return future.stream().map(a -> {
            ClassSession e = byId.get(a.getSessionId());
            return new FutureAttendance(a.getId(), e.getId(), a.getStudentId(), e.getStartsAt(), e.getEndsAt(), e.getModality());
        }).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void moveFutureAttendances(UUID fromCycleId, UUID toCycleId, Modality newModality, UUID overrideBy, String overrideReason) {
        var future = attendances.findFutureByCycle(fromCycleId, clock.instant());
        Map<UUID, ClassSession> byId = eventsOf(future);
        for (SessionAttendance a : future) {
            a.moveToCycle(toCycleId);
            if (overrideBy != null && byId.get(a.getSessionId()).getModality() != newModality) {
                a.markOverride(overrideBy, "Plan renewal with a different modality: " + overrideReason);
            }
        }
        attendances.saveAllAndFlush(future);
    }

    private Map<UUID, ClassSession> eventsOf(List<SessionAttendance> list) {
        return events.findAllById(list.stream().map(SessionAttendance::getSessionId).distinct().toList()).stream()
                .collect(Collectors.toMap(ClassSession::getId, Function.identity()));
    }
}
