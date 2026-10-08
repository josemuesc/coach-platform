package com.coachplatform.auth;

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
            @NotBlank @Size(min = 10, max = 72) String password) {
        @Override
        public String toString() {
            return "RegisterCoachRequest[email=" + email + ", password=<redacted>]";
        }
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=<redacted>]";
        }
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 10, max = 72) String newPassword) {
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

    public record MeResponse(UUID userId, UUID coachId, String role) {
    }
}
