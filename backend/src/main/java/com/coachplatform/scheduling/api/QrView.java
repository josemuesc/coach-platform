package com.coachplatform.scheduling.api;

import java.time.Instant;

/**
 * The rotating code of an event. {@code url} carries the token in the fragment (#), which browsers never send to a server, so it
 * does not reach access logs. The frontend refreshes it before {@code expiresAt}. The token is never printed by toString.
 */
public record QrView(String token, String url, long validForSeconds, Instant expiresAt) {

    @Override
    public String toString() {
        return "QrView[token=<redacted>, url=<redacted>, validForSeconds=" + validForSeconds + ", expiresAt=" + expiresAt + "]";
    }
}
