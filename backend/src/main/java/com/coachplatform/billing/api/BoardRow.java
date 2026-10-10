package com.coachplatform.billing.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of the coach's student board. Everything the screen shows or filters by is decided here; the client only words it.
 *
 * @param status          the chip (see {@link BoardStatus} for the precedence)
 * @param expiringSoon    filter flag "Por vencer": few days or few classes left, or the cycle is COMPLETED (no classes left). False for a suspended student
 * @param noPlan          filter flag "Sin plan": no cycle yet, or the last one expired. False for a suspended student
 * @param expiringBy      null unless {@code expiringSoon}. A COMPLETED cycle is always CLASSES
 * @param planModality    modality of the latest cycle when it is ACTIVE or COMPLETED; null when {@code noPlan}
 * @param classesIncluded of that cycle; null when {@code noPlan}
 * @param classesRemaining of that cycle (0 when COMPLETED); null when {@code noPlan}
 * @param endDate         last usable day of that cycle; null when {@code noPlan}
 * @param daysUntilEnd    days from today (Bogota) to {@code endDate} for an ACTIVE cycle (0 = ends today; negative only while classes past the deadline are still unmarked); null otherwise
 * @param pendingMarks    classes already started and not marked yet (0 when there is no active cycle)
 */
public record BoardRow(UUID studentId, String fullName, boolean minor, boolean hasAccount, boolean active, BoardStatus status,
                        boolean expiringSoon, boolean noPlan, @Schema(nullable = true) ExpiringBy expiringBy,
                        @Schema(nullable = true) Modality planModality, @Schema(nullable = true) Integer classesIncluded,
                        @Schema(nullable = true) Integer classesRemaining, @Schema(nullable = true) LocalDate endDate,
                        @Schema(nullable = true) Integer daysUntilEnd, int pendingMarks) {
}
