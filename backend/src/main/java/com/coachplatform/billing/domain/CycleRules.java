package com.coachplatform.billing.domain;

import static com.coachplatform.billing.domain.CycleRuleException.Code.ACTIVE_CYCLE_EXISTS;
import static com.coachplatform.billing.domain.CycleRuleException.Code.CYCLE_NOT_ACTIVE;
import static com.coachplatform.billing.domain.CycleRuleException.Code.EXTENSION_LIMIT_EXCEEDED;
import static com.coachplatform.billing.domain.CycleRuleException.Code.INVALID_EXTENSION;
import static com.coachplatform.billing.domain.CycleRuleException.Code.INVALID_PAYMENT_DATE;
import static com.coachplatform.billing.domain.CycleRuleException.Code.INVALID_PLAN;
import static com.coachplatform.billing.domain.CycleRuleException.Code.PENDING_SESSIONS_TO_MARK;
import static com.coachplatform.billing.domain.CycleRuleException.Code.REOPEN_NOT_ALLOWED;

import com.coachplatform.billing.api.CycleStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Business rules of the cycle lifecycle. Pure functions over {@link CycleState}; the clock comes from the calendar. */
public final class CycleRules {

    public static final int MAX_BACKDATED_DAYS = 3;

    private final CycleCalendar calendar;

    public CycleRules(CycleCalendar calendar) {
        this.calendar = calendar;
    }

    /**
     * The state a cycle really has today, whatever is stored:
     * all classes used -> COMPLETED (even before the deadline); past the deadline with classes left -> EXPIRED.
     * The deadline day itself is still usable. COMPLETED wins when both apply.
     * A cycle past its deadline does NOT expire while classes that already started are still unmarked
     * ({@code pendingMarks > 0}): the coach must decide those first, otherwise classes would be lost undecided.
     */
    public CycleState evaluate(CycleState stored, int pendingMarks) {
        if (!stored.isActive()) {
            return stored;
        }
        LocalDate today = calendar.today();
        if (stored.classesUsed() >= stored.classesIncluded()) {
            return stored.completed(today);
        }
        if (today.isAfter(stored.endDate()) && pendingMarks == 0) {
            return stored.expired();
        }
        return stored;
    }

    public CycleState evaluate(CycleState stored) {
        return evaluate(stored, 0);
    }

    public record OpenCycleResult(CycleState newCycle, Optional<CycleState> previousUpdated) {
    }

    /**
     * Opens a new cycle for a payment.
     *
     * @param requestedPaidOn payment date, null = today; never in the future, at most 3 days back
     * @param previous        the student's most recent cycle in any status, as stored
     * @return the new cycle (starts on the payment date, ends one month later) and, if the previous cycle must be
     *         persisted with a different status (lazy close, or renewal on its deadline day), its updated state
     */
    public OpenCycleResult openCycle(LocalDate requestedPaidOn, int classesIncluded, Optional<CycleState> previous) {
        return openCycle(requestedPaidOn, classesIncluded, previous, 0);
    }

    /** @param previousPendingMarks classes of the previous cycle that already started and are still unmarked */
    public OpenCycleResult openCycle(LocalDate requestedPaidOn, int classesIncluded, Optional<CycleState> previous,
                                     int previousPendingMarks) {
        if (classesIncluded <= 0) {
            throw new CycleRuleException(INVALID_PLAN, "A plan must include at least one class");
        }
        LocalDate today = calendar.today();
        LocalDate paidOn = requestedPaidOn == null ? today : requestedPaidOn;
        if (paidOn.isAfter(today)) {
            throw new CycleRuleException(INVALID_PAYMENT_DATE, "The payment date cannot be in the future");
        }
        if (paidOn.isBefore(today.minusDays(MAX_BACKDATED_DAYS))) {
            throw new CycleRuleException(INVALID_PAYMENT_DATE,
                    "The payment date cannot be more than " + MAX_BACKDATED_DAYS + " days in the past");
        }

        Optional<CycleState> previousUpdated = Optional.empty();
        if (previous.isPresent()) {
            CycleState stored = previous.get();
            CycleState effective = evaluate(stored, previousPendingMarks);
            if (effective.isActive()) {
                // Still active: renewing is only allowed from its deadline day onwards.
                if (today.isBefore(effective.endDate())) {
                    throw new CycleRuleException(ACTIVE_CYCLE_EXISTS,
                            "The student already has an active cycle until " + effective.endDate());
                }
                if (previousPendingMarks > 0) {
                    throw new CycleRuleException(PENDING_SESSIONS_TO_MARK,
                            "The previous cycle has " + previousPendingMarks + " class(es) still to be marked");
                }
                if (paidOn.isBefore(effective.endDate())) {
                    throw new CycleRuleException(INVALID_PAYMENT_DATE,
                            "The payment date cannot be before the end of the previous cycle");
                }
                effective = effective.expired(); // renewal on the deadline day: leftovers are lost
            } else {
                LocalDate previousEnd = effective.status() == CycleStatus.COMPLETED
                        ? effective.completedOn() : effective.endDate();
                if (paidOn.isBefore(previousEnd)) {
                    throw new CycleRuleException(INVALID_PAYMENT_DATE,
                            "The payment date cannot be before the end of the previous cycle");
                }
            }
            if (!effective.equals(stored)) {
                previousUpdated = Optional.of(effective);
            }
        }

        LocalDate end = calendar.endDateFor(paidOn);
        CycleState created = new CycleState(paidOn, end, end, classesIncluded, 0, CycleStatus.ACTIVE, null);
        return new OpenCycleResult(created, previousUpdated);
    }

    /** Counts one class as seen. Closes the cycle as COMPLETED when the last class is used, even before the deadline. */
    public CycleState consumeClass(CycleState stored) {
        return consumeClass(stored, 0);
    }

    /** @param pendingMarks unmarked started classes INCLUDING the one being marked: keeps an overdue cycle open for it */
    public CycleState consumeClass(CycleState stored, int pendingMarks) {
        CycleState effective = evaluate(stored, pendingMarks);
        if (!effective.isActive()) {
            throw new CycleRuleException(CYCLE_NOT_ACTIVE, "The cycle is " + effective.status());
        }
        CycleState used = effective.withClassesUsed(effective.classesUsed() + 1);
        return used.classesUsed() == used.classesIncluded() ? used.completed(calendar.today()) : used;
    }

    public record ExtensionRecord(LocalDate previousEndDate, LocalDate newEndDate, UUID extendedBy, Instant extendedAt,
                                  boolean reopened) {
    }

    public record ExtensionResult(CycleState cycle, ExtensionRecord record) {
    }

    public ExtensionResult extend(CycleState stored, LocalDate newEndDate, UUID extendedBy, int maxExtensionDays) {
        return extend(stored, newEndDate, extendedBy, maxExtensionDays, 0, false);
    }

    /**
     * The coach moves the deadline of a cycle; who and when are captured in the record.
     * <ul>
     *   <li>The TOTAL extension is capped: the new deadline cannot be more than {@code maxExtensionDays} after the
     *       ORIGINAL deadline, however many times the cycle is extended.</li>
     *   <li>An EXPIRED cycle can be REOPENED the same way (it becomes ACTIVE again) unless a newer cycle exists or it
     *       closed as COMPLETED. The new deadline must then be today or later, or it would expire at once.</li>
     * </ul>
     *
     * @param pendingMarks   unmarked started classes of this cycle
     * @param hasNewerCycle  the student already has a more recent cycle
     */
    public ExtensionResult extend(CycleState stored, LocalDate newEndDate, UUID extendedBy, int maxExtensionDays,
                                  int pendingMarks, boolean hasNewerCycle) {
        CycleState effective = evaluate(stored, pendingMarks);
        boolean reopening = false;
        if (effective.status() == CycleStatus.COMPLETED) {
            throw new CycleRuleException(REOPEN_NOT_ALLOWED, "A completed cycle cannot be extended or reopened");
        }
        if (effective.status() == CycleStatus.EXPIRED) {
            if (hasNewerCycle) {
                throw new CycleRuleException(REOPEN_NOT_ALLOWED,
                        "An expired cycle cannot be reopened once the student has a newer cycle");
            }
            if (newEndDate.isBefore(calendar.today())) {
                throw new CycleRuleException(INVALID_EXTENSION, "A reopened cycle needs a deadline of today or later");
            }
            reopening = true;
        }
        if (!newEndDate.isAfter(effective.endDate())) {
            throw new CycleRuleException(INVALID_EXTENSION, "The new deadline must be after " + effective.endDate());
        }
        LocalDate latestAllowed = effective.originalEndDate().plusDays(maxExtensionDays);
        if (newEndDate.isAfter(latestAllowed)) {
            throw new CycleRuleException(EXTENSION_LIMIT_EXCEEDED, "A cycle cannot be extended more than "
                    + maxExtensionDays + " days past its original deadline (latest allowed: " + latestAllowed + ")");
        }
        CycleState result = reopening ? effective.reopened(newEndDate) : effective.withEndDate(newEndDate);
        return new ExtensionResult(result,
                new ExtensionRecord(effective.endDate(), newEndDate, extendedBy, calendar.now(), reopening));
    }
}
