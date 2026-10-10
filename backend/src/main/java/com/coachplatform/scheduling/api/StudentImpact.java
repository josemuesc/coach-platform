package com.coachplatform.scheduling.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What a block left for one student whose classes it released, with the state AFTER the release. atRisk = classes still to book and
 * fewer free places before the deadline than that; canExtend = the server would accept extending the cycle (the coach decides:
 * nothing is extended automatically). cycleEndDate is null only when the student has no active cycle any more.
 */
public record StudentImpact(UUID studentId, String studentName, boolean minor, int releasedClasses, int classesLeftToSchedule,
                            int freeSlotsBeforeDeadline, @Schema(nullable = true) LocalDate cycleEndDate, boolean atRisk,
                            boolean canExtend) {
}
