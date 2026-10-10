package com.coachplatform.billing.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * Whether the latest cycle's deadline can be moved (or an expired one reopened), with the range of dates the server accepts: after the
 * current deadline (today or later when reopening) and never beyond the original deadline plus the coach's maximum extension.
 *
 * @param extendFrom  earliest accepted new deadline; null when not {@code canExtendCycle}
 * @param extendUntil latest accepted new deadline; null when not {@code canExtendCycle}
 */
public record ExtensionAvailability(boolean canExtendCycle, @Schema(nullable = true) LocalDate extendFrom,
                                    @Schema(nullable = true) LocalDate extendUntil) {
}
