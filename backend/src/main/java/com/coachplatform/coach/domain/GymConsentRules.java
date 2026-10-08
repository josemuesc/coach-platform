package com.coachplatform.coach.domain;

import java.time.Instant;

/**
 * The coach's own record that they hold the gym's informed consent (a flag and a date, never the content). The date is set
 * by the server when the flag turns on, kept while it stays on, and cleared when it turns off.
 */
public final class GymConsentRules {

    public record State(boolean confirmed, Instant confirmedAt) {
    }

    public State apply(State current, boolean requested, Instant now) {
        if (!requested) {
            return new State(false, null);
        }
        return current.confirmed() && current.confirmedAt() != null ? current : new State(true, now);
    }
}
