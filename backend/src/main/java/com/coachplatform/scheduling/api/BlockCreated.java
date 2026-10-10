package com.coachplatform.scheduling.api;

import java.util.List;

/** The block is saved and the listed classes were released (cancelled by the coach, nobody lost a class), all in one operation. */
public record BlockCreated(BlockSummary block, List<AffectedClass> cancelled, List<StudentImpact> students, int markedUntouched,
                           int pendingUntouched) {
}
