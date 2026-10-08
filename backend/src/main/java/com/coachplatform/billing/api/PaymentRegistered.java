package com.coachplatform.billing.api;

import java.time.LocalDate;
import java.util.UUID;

public record PaymentRegistered(UUID paymentId, UUID cycleId, LocalDate startDate, LocalDate endDate) {
}
