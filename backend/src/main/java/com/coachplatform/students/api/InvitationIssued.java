package com.coachplatform.students.api;

import java.time.Instant;

public record InvitationIssued(String token, Instant expiresAt) {

    @Override
    public String toString() {
        return "InvitationIssued[expiresAt=" + expiresAt + ", token=<redacted>]";
    }
}
