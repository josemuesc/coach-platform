package com.coachplatform.scheduling.api;

/** Where an event is relative to the server's clock: already over, in progress, or still to come. Decided by the server. */
public enum EventPhase {
    PAST,
    NOW,
    UPCOMING
}
