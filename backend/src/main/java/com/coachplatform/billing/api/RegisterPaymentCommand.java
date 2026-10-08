package com.coachplatform.billing.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.UUID;

/** amountCop defaults to the plan price; paidOn defaults to today (America/Bogota), at most 3 days back, never future. */
public record RegisterPaymentCommand(
        @NotNull UUID planId,
        @Positive Long amountCop,
        @NotNull PaymentMethod method,
        LocalDate paidOn) {
}
