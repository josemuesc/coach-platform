package com.coachplatform.billing.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of the coach's student board. Everything the screen shows or filters by is decided here; the client only words it.
 *
 * @param status          the chip (see {@link BoardStatus} for the precedence)
 * @param activeCycle     filter "Activos": the student is not suspended and their latest cycle is ACTIVE. "Inactivos" is the opposite
 * @param expiringSoon    filter "Por vencer", a subset of "Activos": few days or few classes left
 * @param needsRenewal    the student is not suspended and their latest cycle is COMPLETED (all classes used) or EXPIRED: shown as "renovar" and listed first among the inactive
 * @param expiringBy      null unless {@code expiringSoon}
 * @param planModality    modality of the LATEST cycle (whatever its state); null when the student never paid
 * @param classesIncluded of that cycle; null when the student never paid
 * @param classesRemaining of that cycle (0 when COMPLETED, the ones lost when EXPIRED); null when the student never paid
 * @param endDate         last usable day of that cycle; null when the student never paid
 * @param daysUntilEnd    days from today (Bogota) to {@code endDate} for an ACTIVE cycle (0 = ends today; negative only while classes past the deadline are still unmarked); null otherwise
 * @param pendingMarks    classes already started and not marked yet (0 when there is no active cycle)
 */
public record BoardRow(UUID studentId, String fullName, boolean minor, boolean hasAccount, boolean active, BoardStatus status,
                        boolean activeCycle, boolean expiringSoon, boolean needsRenewal, @Schema(nullable = true) ExpiringBy expiringBy,
                        @Schema(nullable = true) Modality planModality, @Schema(nullable = true) Integer classesIncluded,
                        @Schema(nullable = true) Integer classesRemaining, @Schema(nullable = true) LocalDate endDate,
                        @Schema(nullable = true) Integer daysUntilEnd, int pendingMarks) {
}
