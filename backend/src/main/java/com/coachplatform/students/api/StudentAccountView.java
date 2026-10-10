package com.coachplatform.students.api;

import com.coachplatform.auth.api.PasswordChangeMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * The coach's view of a student's login (for a minor: the guardian's). Never says anything secret.
 *
 * @param hasAccount        the invitation was accepted
 * @param active            the student (and their account) is not suspended
 * @param passwordChangedAt when the password last changed; null if it never did, and always null without an account
 * @param passwordChangedBy SELF (the person changed it) or COACH_LINK (set through a reset link the coach handed over); null with passwordChangedAt
 * @param openResetLinkUntil expiry of a reset link the coach generated that can still be used; null when there is none
 */
public record StudentAccountView(boolean hasAccount, boolean active, @Schema(nullable = true) Instant passwordChangedAt,
                                 @Schema(nullable = true) PasswordChangeMethod passwordChangedBy,
                                 @Schema(nullable = true) Instant openResetLinkUntil) {
}
