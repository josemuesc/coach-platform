package com.coachplatform.scheduling.api;

import java.time.Instant;

/**
 * The rotating code of an event. {@code url} carries the token in the fragment (#), which browsers never send to a server, so it
 * does not reach access logs. The code itself changes every 30 s; the frontend asks for a new one before {@code expiresAt}.
 * {@code availableFrom} / {@code availableUntil}: the span in which the coach can show the code (the coach's own settings applied to
 * this event). The token is never printed by toString.
 */
public record QrView(String token, String url, long validForSeconds, Instant expiresAt, Instant availableFrom, Instant availableUntil) {

    @Override
    public String toString() {
        return "QrView[token=<redacted>, url=<redacted>, validForSeconds=" + validForSeconds + ", expiresAt=" + expiresAt + "]";
    }
}
