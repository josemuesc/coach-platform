package com.coachplatform.billing.domain;

import com.coachplatform.billing.api.BoardStatus;
import com.coachplatform.billing.api.CycleStatus;
import com.coachplatform.billing.api.ExpiringBy;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Classifies a student for the coach's board from their LATEST cycle (as it stands today). Pure: the caller passes today (Bogota) and
 * the coach's thresholds. "Active" means a cycle that is ACTIVE: a COMPLETED one (every class used, even before its deadline) or an
 * EXPIRED one is NOT active, it is something to renew.
 */
public final class StudentBoardRules {

    private StudentBoardRules() {
    }

    /** The latest cycle's effective state, in plain values. */
    public record LatestCycle(CycleStatus status, int classesRemaining, LocalDate endDate) {
    }

    /** @param daysUntilEnd only for an ACTIVE cycle; null otherwise */
    public record Classification(BoardStatus status, boolean activeCycle, boolean expiringSoon, boolean needsRenewal, ExpiringBy expiringBy,
                                 Integer daysUntilEnd) {
    }

    /**
     * @param latest          the latest cycle, or null when the student never paid
     * @param expiringDays    "few days": at most this many days left (inclusive)
     * @param expiringClasses "few classes": at most this many classes left (inclusive)
     */
    public static Classification classify(boolean active, boolean hasAccount, LatestCycle latest, LocalDate today, int expiringDays,
                                          int expiringClasses) {
        CycleStatus cycle = latest == null ? null : latest.status();
        Integer days = cycle == CycleStatus.ACTIVE ? (int) ChronoUnit.DAYS.between(today, latest.endDate()) : null;
        boolean byDays = days != null && days <= expiringDays;
        boolean byClasses = cycle == CycleStatus.ACTIVE && latest.classesRemaining() <= expiringClasses;

        if (!active) {
            // out of every bucket: a suspended student is listed (as inactive), whatever their cycle says
            return new Classification(BoardStatus.SUSPENDIDO, false, false, false, null, days);
        }
        boolean activeCycle = cycle == CycleStatus.ACTIVE;
        boolean expiringSoon = activeCycle && (byDays || byClasses);
        ExpiringBy by = !expiringSoon ? null : byDays && byClasses ? ExpiringBy.BOTH : byDays ? ExpiringBy.DAYS : ExpiringBy.CLASSES;
        boolean needsRenewal = cycle == CycleStatus.COMPLETED || cycle == CycleStatus.EXPIRED;

        BoardStatus status;
        if (!hasAccount) {
            status = BoardStatus.SIN_ACTIVAR;
        } else if (cycle == CycleStatus.COMPLETED) {
            status = BoardStatus.SIN_CLASES;
        } else if (cycle == CycleStatus.EXPIRED) {
            status = BoardStatus.VENCIDO;
        } else if (cycle == null) {
            status = BoardStatus.SIN_PLAN;
        } else {
            status = expiringSoon ? BoardStatus.POR_VENCER : BoardStatus.AL_DIA;
        }
        return new Classification(status, activeCycle, expiringSoon, needsRenewal, by, days);
    }
}
