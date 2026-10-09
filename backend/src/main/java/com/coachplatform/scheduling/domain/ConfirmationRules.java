package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.CLASS_NOT_STARTED;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.CONFIRMATION_WINDOW_CLOSED;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.CYCLE_CLOSED;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.INVALID_STATE;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.QR_NOT_OPEN_YET;
import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.QR_WINDOW_CLOSED;

import com.coachplatform.scheduling.api.AttendanceStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The student's confirmation of a class. A confirmation is permanent and, on its own, never changes the status nor the
 * cycle count. The only thing that can mark a class is a valid QR scan of a class still SCHEDULED (the same marking the
 * coach does, which does consume the class).
 *
 * <p>Repeating a confirmation is idempotent: it is reported as {@code alreadyConfirmed}, never an error, never a second
 * deduction.
 */
public final class ConfirmationRules {

    /** @param markAttended the scan marks the class ATTENDED (consumes one class) @param recordConfirmation write the confirmation */
    public record Outcome(boolean markAttended, boolean recordConfirmation, boolean alreadyConfirmed) {
    }

    private final Clock clock;

    public ConfirmationRules(Clock clock) {
        this.clock = clock;
    }

    /**
     * The coach may ask for the code from {@code opensMinutesBefore} before the start until {@code closesHoursAfterEnd} after
     * the end (both limits inclusive). Both numbers are coach settings (15 minutes / 2 hours by default).
     */
    public void requireMayIssue(Instant startsAt, Instant endsAt, int opensMinutesBefore, int closesHoursAfterEnd) {
        Instant now = clock.instant();
        if (now.isBefore(startsAt.minus(Duration.ofMinutes(opensMinutesBefore)))) {
            throw new SchedulingRuleException(QR_NOT_OPEN_YET,
                    "The code is available from " + opensMinutesBefore + " minutes before the class starts");
        }
        requireNotAfterWindow(endsAt, closesHoursAfterEnd);
    }

    /**
     * A valid code was scanned by the student who owns {@code status}. The scan counts from the class's start (before that
     * it is CLASS_NOT_STARTED, because a scan marks the class) until {@code closesHoursAfterEnd} after its end (inclusive).
     * SCHEDULED -> marked ATTENDED and confirmed. Already ATTENDED or NO_SHOW (marked by the coach) -> only the confirmation is
     * added; a NO_SHOW is NOT flipped, the coach decides.
     */
    public Outcome scan(AttendanceStatus status, boolean alreadyConfirmed, Instant startsAt, Instant endsAt, boolean cycleActive,
                        int closesHoursAfterEnd) {
        requireLiveAndStarted(status, startsAt);
        requireNotAfterWindow(endsAt, closesHoursAfterEnd);
        if (alreadyConfirmed && status != AttendanceStatus.SCHEDULED) {
            return new Outcome(false, false, true);
        }
        if (status == AttendanceStatus.SCHEDULED) {
            if (!cycleActive) {
                throw new SchedulingRuleException(CYCLE_CLOSED, "The class's cycle is no longer active");
            }
            return new Outcome(true, !alreadyConfirmed, alreadyConfirmed);
        }
        return new Outcome(false, true, false);
    }

    /** Confirming afterwards, from the history: only records the confirmation, within {@code windowHours} of the start. */
    public Outcome confirmLater(AttendanceStatus status, boolean alreadyConfirmed, Instant startsAt, int windowHours) {
        requireLiveAndStarted(status, startsAt);
        if (alreadyConfirmed) {
            return new Outcome(false, false, true);
        }
        if (clock.instant().isAfter(startsAt.plus(Duration.ofHours(windowHours)))) {
            throw new SchedulingRuleException(CONFIRMATION_WINDOW_CLOSED,
                    "A class can only be confirmed within " + windowHours + " hours of its start");
        }
        return new Outcome(false, true, false);
    }

    /** Whether the student could still confirm this class from their history right now (what the frontend shows as a button). */
    public boolean canConfirmLater(AttendanceStatus status, boolean alreadyConfirmed, Instant startsAt, int windowHours) {
        Instant now = clock.instant();
        return status.isLive() && !alreadyConfirmed && !now.isBefore(startsAt) && !now.isAfter(startsAt.plus(Duration.ofHours(windowHours)));
    }

    /** Marked by the coach and never confirmed by the student. */
    public static boolean isOnlyMarkedByCoach(AttendanceStatus status, boolean confirmed) {
        return (status == AttendanceStatus.ATTENDED || status == AttendanceStatus.NO_SHOW) && !confirmed;
    }

    private void requireNotAfterWindow(Instant endsAt, int closesHoursAfterEnd) {
        if (clock.instant().isAfter(endsAt.plus(Duration.ofHours(closesHoursAfterEnd)))) {
            throw new SchedulingRuleException(QR_WINDOW_CLOSED,
                    "The code can only be used until " + closesHoursAfterEnd + " hours after the class ends");
        }
    }

    private void requireLiveAndStarted(AttendanceStatus status, Instant startsAt) {
        if (!status.isLive()) {
            throw new SchedulingRuleException(INVALID_STATE, "A " + status + " class cannot be confirmed");
        }
        if (clock.instant().isBefore(startsAt)) {
            throw new SchedulingRuleException(CLASS_NOT_STARTED,
                    "The class has not started yet: attendance can only be confirmed once it begins");
        }
    }
}
