package com.coachplatform.billing.domain;

import com.coachplatform.billing.api.BoardStatus;
import com.coachplatform.billing.api.CycleStatus;
import com.coachplatform.billing.api.ExpiringBy;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Classifies a student for the coach's board from their LATEST cycle (as it stands today). Pure: the caller passes today (Bogota) and
 * the coach's thresholds. A COMPLETED cycle (every class used, possibly before its deadline) is "about to expire by CLASSES": the student
 * has nothing left and needs to renew. An expired or missing cycle is "no plan".
 */
public final class StudentBoardRules {

    private StudentBoardRules() {
    }

    /** The latest cycle's effective state, in plain values. */
    public record LatestCycle(CycleStatus status, int classesRemaining, LocalDate endDate) {
    }

    /** @param daysUntilEnd only for an ACTIVE cycle; null otherwise */
    public record Classification(BoardStatus status, boolean expiringSoon, boolean noPlan, ExpiringBy expiringBy, Integer daysUntilEnd) {
    }

    /**
     * @param latest         the latest cycle, or null when the student never paid
     * @param expiringDays   "few days": at most this many days left
     * @param expiringClasses "few classes": at most this many classes left
     */
    public static Classification classify(boolean active, boolean hasAccount, LatestCycle latest, LocalDate today, int expiringDays,
                                          int expiringClasses) {
        boolean noPlan = latest == null || latest.status() == CycleStatus.EXPIRED;
        boolean expiringSoon = false;
        ExpiringBy by = null;
        Integer days = null;
        if (!noPlan && latest.status() == CycleStatus.COMPLETED) {
            expiringSoon = true;
            by = ExpiringBy.CLASSES;
        } else if (!noPlan) {
            days = (int) ChronoUnit.DAYS.between(today, latest.endDate());
            boolean byDays = days <= expiringDays;
            boolean byClasses = latest.classesRemaining() <= expiringClasses;
            expiringSoon = byDays || byClasses;
            by = byDays && byClasses ? ExpiringBy.BOTH : byDays ? ExpiringBy.DAYS : byClasses ? ExpiringBy.CLASSES : null;
        }
        if (!active) {
            // out of every filter: a suspended student is listed, but belongs to none of the buckets
            return new Classification(BoardStatus.SUSPENDIDO, false, false, null, days);
        }
        BoardStatus status = !hasAccount ? BoardStatus.SIN_ACTIVAR
                : noPlan ? BoardStatus.SIN_PLAN
                : expiringSoon ? BoardStatus.POR_VENCER
                : BoardStatus.AL_DIA;
        return new Classification(status, expiringSoon, noPlan, by, days);
    }
}
