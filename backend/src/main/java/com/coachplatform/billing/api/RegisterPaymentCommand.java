package com.coachplatform.billing.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A manual payment. planId, amountCop and method are MANDATORY: the amount actually received is always stated, never assumed
 * from the plan's price (the client can prefill it). paidOn is optional and defaults to today (America/Bogota), at most 3 days
 * back, never future.
 *
 * <p>overrideModality (optional, defaults to false) / overrideReason: when renewing on the deadline day with classes already booked
 * in events of ANOTHER modality than the new plan, the payment is refused unless the coach forces the transfer with a reason (audited).
 */
public record RegisterPaymentCommand(
        @NotNull UUID planId,
        @NotNull @Positive Long amountCop,
        @NotNull PaymentMethod method,
        LocalDate paidOn,
        Boolean overrideModality,
        String overrideReason) {

    public RegisterPaymentCommand {
        if (overrideModality == null) {
            overrideModality = Boolean.FALSE;
        }
    }

    public RegisterPaymentCommand(UUID planId, Long amountCop, PaymentMethod method, LocalDate paidOn) {
        this(planId, amountCop, method, paidOn, Boolean.FALSE, null);
    }
}
