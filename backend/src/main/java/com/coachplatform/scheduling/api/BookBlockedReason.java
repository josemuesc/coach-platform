package com.coachplatform.scheduling.api;

/** Why a student cannot be booked right now. */
public enum BookBlockedReason {
    NO_ACTIVE_CYCLE,
    NO_CLASSES_LEFT
}
