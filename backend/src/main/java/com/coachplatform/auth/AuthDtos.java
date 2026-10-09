package com.coachplatform.auth;

import com.coachplatform.auth.api.PasswordChangeMethod;
import com.coachplatform.common.ValidPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterCoachRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Email @Size(max = 320) String email,
            @ValidPassword String password) {
        @Override
        public String toString() {
            return "RegisterCoachRequest[email=" + email + ", password=<redacted>]";
        }
    }

    public record LoginRequest(@NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 1000) String password) {
        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=<redacted>]";
        }
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @ValidPassword String newPassword) {
        @Override
        public String toString() {
            return "ChangePasswordRequest[<redacted>]";
        }
    }

    public record AuthResponse(String token, String role, UUID coachId) {
        @Override
        public String toString() {
            return "AuthResponse[role=" + role + ", coachId=" + coachId + ", token=<redacted>]";
        }
    }

    /**
     * @param passwordChangedAt when the password last changed (null = never: it is still the one set at registration/invitation)
     * @param passwordChangedBy SELF, or COACH_LINK when it was set through a reset link the coach handed over (the frontend warns
     *                          the student in that case); null if never changed
     */
    public record MeResponse(UUID userId, UUID coachId, String role, java.time.Instant passwordChangedAt,
                             PasswordChangeMethod passwordChangedBy) {
    }
}
