package com.coachplatform.auth.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** When and how a login's password last changed; both null while it was never changed after the account was created. */
public record PasswordInfo(@Schema(nullable = true) Instant changedAt, @Schema(nullable = true) PasswordChangeMethod method) {
}
