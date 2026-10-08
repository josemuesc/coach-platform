package com.coachplatform.scheduling;

import com.coachplatform.billing.api.CycleSessions;
import com.coachplatform.billing.api.PendingSession;
import com.coachplatform.scheduling.api.SessionStatus;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the one internal port ({@link CycleSessions}) so billing can ask about the classes of a cycle without
 * depending on this module. It only reads/moves rows; the student lock is already held by the caller.
 */
@Component
class SchedulingCycleSessions implements CycleSessions {

    private final ClassSessionRepository sessions;
    private final Clock clock;

    SchedulingCycleSessions(ClassSessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<PendingSession> pendingMarks(UUID cycleId) {
        return sessions.findByCycleIdAndStatusAndStartsAtLessThanEqualOrderByStartsAt(cycleId, SessionStatus.SCHEDULED, clock.instant())
                .stream().map(s -> new PendingSession(s.getId(), s.getStudentId(), s.getStartsAt(), s.getEndsAt())).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public int pendingMarkCount(UUID cycleId) {
        return sessions.countByCycleIdAndStatusAndStartsAtLessThanEqual(cycleId, SessionStatus.SCHEDULED, clock.instant());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public int futureScheduledCount(UUID cycleId) {
        return sessions.findByCycleIdAndStatusAndStartsAtGreaterThan(cycleId, SessionStatus.SCHEDULED, clock.instant()).size();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void moveFutureSessions(UUID fromCycleId, UUID toCycleId) {
        var future = sessions.findByCycleIdAndStatusAndStartsAtGreaterThan(fromCycleId, SessionStatus.SCHEDULED, clock.instant());
        future.forEach(s -> s.moveToCycle(toCycleId));
        sessions.saveAllAndFlush(future);
    }
}
