package com.coachplatform.billing.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

public record StudentBillingOverview(UUID studentId, String fullName, OverviewStatus status, @Schema(nullable = true) LocalDate endDate,
                                     @Schema(nullable = true) Integer classesRemaining, int pendingMarks) {
}
