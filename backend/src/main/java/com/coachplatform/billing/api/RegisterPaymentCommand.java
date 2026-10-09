package com.coachplatform.billing.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A manual payment. planId, amountCop and method are MANDATORY: the amount actually received is always stated, never assumed
 * from the plan's price (the client can prefill it). paidOn is optional and defaults to today (America/Bogota), at most 3 days
 * back, never future.
 *
 * <p>reference (optional): the receipt number. The server trims it, turns a blank into none and refuses line breaks, control characters,
 * more than 100 characters and any run of 12 or more digits (a possible card or account number). The payment is immutable once stored.
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
        String overrideReason,
        @Size(max = 300) String reference) {

    public RegisterPaymentCommand {
        if (overrideModality == null) {
            overrideModality = Boolean.FALSE;
        }
    }

    public RegisterPaymentCommand(UUID planId, Long amountCop, PaymentMethod method, LocalDate paidOn) {
        this(planId, amountCop, method, paidOn, Boolean.FALSE, null, null);
    }
}
