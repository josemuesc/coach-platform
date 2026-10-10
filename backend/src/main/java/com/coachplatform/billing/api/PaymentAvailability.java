package com.coachplatform.billing.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * Whether the coach can register a payment right now, derived from the SAME rule the server applies when the payment arrives.
 *
 * @param blockedBy     null when {@code canRegisterPayment}
 * @param opensOn       when blockedBy = ACTIVE_CYCLE: the cycle's last day, from which a payment is accepted (or earlier, as soon as the
 *                      student uses their last class); null otherwise
 * @param paidOnMin     earliest accepted payment date; null when the payment cannot be registered
 * @param paidOnMax     latest accepted payment date (today, Bogota); null when the payment cannot be registered
 */
public record PaymentAvailability(boolean canRegisterPayment, @Schema(nullable = true) PaymentBlockedBy blockedBy,
                                  @Schema(nullable = true) LocalDate opensOn, @Schema(nullable = true) LocalDate paidOnMin,
                                  @Schema(nullable = true) LocalDate paidOnMax) {
}
