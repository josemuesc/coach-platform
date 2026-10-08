package com.coachplatform.billing.api;

import java.time.LocalDate;
import java.util.UUID;

/** Effective state: computed against today's date, so an overdue cycle shows as EXPIRED even before the job closes it. */
public record CycleSummary(UUID id, UUID studentId, UUID planId, LocalDate startDate, LocalDate endDate,
                           LocalDate originalEndDate, int classesIncluded, int classesUsed, int classesRemaining,
                           int classesLost, CycleStatus status) {
}
