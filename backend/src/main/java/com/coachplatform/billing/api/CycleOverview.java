package com.coachplatform.billing.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The cycle part of a student's profile: the LATEST cycle (active, completed or expired; null when the student never paid), its plan's
 * name, the last payment, and what the coach may do about it now.
 *
 * @param planName the plan the cycle was opened with (its current name); null when there is no cycle
 */
public record CycleOverview(@Schema(nullable = true) CycleSummary cycle, @Schema(nullable = true) String planName,
                            @Schema(nullable = true) PaymentSummary lastPayment, PaymentAvailability payment, ExtensionAvailability extension) {
}
