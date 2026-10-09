package com.coachplatform.students.api;

import java.time.Instant;

public record PasswordResetIssued(String token, Instant expiresAt) {

    @Override
    public String toString() {
        return "PasswordResetIssued[expiresAt=" + expiresAt + ", token=<redacted>]";
    }
}
