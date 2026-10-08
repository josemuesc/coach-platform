package com.coachplatform.billing.domain;

import com.coachplatform.billing.api.CycleStatus;
import java.time.LocalDate;

/**
 * Immutable snapshot of a cycle using plain values. {@code endDate} is the LAST usable day (inclusive).
 * {@code originalEndDate} is the deadline when the cycle opened (extensions only move {@code endDate}).
 * {@code completedOn} is only set when the cycle closed because all its classes were used.
 */
public record CycleState(LocalDate startDate, LocalDate endDate, LocalDate originalEndDate, int classesIncluded,
                         int classesUsed, CycleStatus status, LocalDate completedOn) {

    public CycleState {
        if (classesIncluded <= 0 || classesUsed < 0 || classesUsed > classesIncluded) {
            throw new IllegalArgumentException("invalid class counters");
        }
        if (!originalEndDate.isAfter(startDate) || endDate.isBefore(originalEndDate)) {
            throw new IllegalArgumentException("deadlines must satisfy start < originalEnd <= end");
        }
    }

    public int classesRemaining() {
        return classesIncluded - classesUsed;
    }

    /** Classes forfeited when the cycle expired (no carry-over to the next cycle). */
    public int classesLost() {
        return status == CycleStatus.EXPIRED ? classesRemaining() : 0;
    }

    public boolean isActive() {
        return status == CycleStatus.ACTIVE;
    }

    CycleState completed(LocalDate on) {
        return new CycleState(startDate, endDate, originalEndDate, classesIncluded, classesUsed, CycleStatus.COMPLETED, on);
    }

    CycleState expired() {
        return new CycleState(startDate, endDate, originalEndDate, classesIncluded, classesUsed, CycleStatus.EXPIRED, null);
    }

    CycleState withClassesUsed(int used) {
        return new CycleState(startDate, endDate, originalEndDate, classesIncluded, used, status, completedOn);
    }

    CycleState withEndDate(LocalDate newEnd) {
        return new CycleState(startDate, newEnd, originalEndDate, classesIncluded, classesUsed, status, completedOn);
    }
}
