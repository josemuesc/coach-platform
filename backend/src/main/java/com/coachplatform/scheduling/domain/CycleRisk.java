package com.coachplatform.scheduling.domain;

/**
 * A cycle is "at risk" when the student still has classes to book but fewer free places than that before the deadline
 * (e.g. after a block released their classes). It is a warning for the coach, who may extend the deadline; nothing is extended
 * automatically.
 */
public final class CycleRisk {

    private CycleRisk() {
    }

    public static boolean atRisk(int classesLeftToSchedule, int freeSlotsBeforeDeadline) {
        return classesLeftToSchedule > 0 && freeSlotsBeforeDeadline < classesLeftToSchedule;
    }
}
