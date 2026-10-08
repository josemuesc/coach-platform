package com.coachplatform.billing.api;

import java.time.LocalDate;
import java.util.UUID;

public record StudentBillingOverview(UUID studentId, String fullName, OverviewStatus status, LocalDate endDate,
                                     Integer classesRemaining, int pendingMarks) {
}
