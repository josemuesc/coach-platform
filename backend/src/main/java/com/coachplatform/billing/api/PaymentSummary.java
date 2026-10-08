package com.coachplatform.billing.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PaymentSummary(UUID id, UUID studentId, UUID cycleId, long amountCop, PaymentMethod method,
                             LocalDate paidOn, UUID recordedBy, Instant createdAt) {
}
