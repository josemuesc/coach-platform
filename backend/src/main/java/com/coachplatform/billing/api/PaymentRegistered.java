package com.coachplatform.billing.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

public record PaymentRegistered(UUID paymentId, UUID cycleId, LocalDate startDate, LocalDate endDate,
                              @Schema(nullable = true) String reference) {
}
