package com.coachplatform.scheduling.api;

/** The state of ONE student's place in an event. */
public enum AttendanceStatus {
    SCHEDULED,
    ATTENDED,
    CANCELLED_ON_TIME,
    RESCHEDULED,
    NO_SHOW,
    CANCELLED_BY_COACH;

    /** Places that hold a seat of the event: booked, or already seen. */
    public boolean isLive() {
        return this == SCHEDULED || this == ATTENDED || this == NO_SHOW;
    }
}
