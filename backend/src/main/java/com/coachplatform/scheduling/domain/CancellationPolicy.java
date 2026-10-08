package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.CANCELLATION_WINDOW_CLOSED;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.CLASS_ALREADY_STARTED;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.INVALID_STATE;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.REASON_REQUIRED;

import com.coachplatform.scheduling.api.SessionStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Who may cancel a class, and when. Always evaluated on the server's clock, never on a client-supplied time. */
public final class CancellationPolicy {

    private final Clock clock;

    public CancellationPolicy(Clock clock) {
        this.clock = clock;
    }

    /** Free cancellation: at least {@code windowHours} before the start (exactly on the limit still counts). */
    public boolean isInsideFreeWindow(Instant startsAt, int windowHours) {
        return !clock.instant().isAfter(startsAt.minus(Duration.ofHours(windowHours)));
    }

    /**
     * A student may cancel only a SCHEDULED class, before it starts and with enough notice. Inside the window the
     * attempt is simply refused: the class stays SCHEDULED and ends up "seen" (attended or no-show) when marked.
     */
    public void requireStudentMayCancel(SessionStatus status, Instant startsAt, int windowHours) {
        if (status != SessionStatus.SCHEDULED) {
            throw new SchedulingRuleException(INVALID_STATE, "Only a scheduled class can be cancelled");
        }
        if (!clock.instant().isBefore(startsAt)) {
            throw new SchedulingRuleException(CLASS_ALREADY_STARTED, "The class has already started");
        }
        if (!isInsideFreeWindow(startsAt, windowHours)) {
            throw new SchedulingRuleException(CANCELLATION_WINDOW_CLOSED,
                    "Classes can only be cancelled at least " + windowHours + " hours before they start");
        }
    }

    /**
     * The coach may ALWAYS cancel a scheduled class (no window, even after the start) but must give a reason. This is
     * also how a late student cancellation is forgiven: the coach cancels it instead of marking it attended/no-show.
     */
    public void requireCoachMayCancel(SessionStatus status, String reason) {
        if (status != SessionStatus.SCHEDULED) {
            throw new SchedulingRuleException(INVALID_STATE, "Only a scheduled class can be cancelled");
        }
        if (reason == null || reason.isBlank()) {
            throw new SchedulingRuleException(REASON_REQUIRED, "A reason is required to cancel as the coach");
        }
    }
}
