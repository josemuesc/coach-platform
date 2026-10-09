package com.coachplatform.security;

import java.util.UUID;

/**
 * @param passwordEpoch the {@code app_user.password_changed_at} (epoch millis, 0 = never changed) the token was issued under;
 *                      a token whose epoch is no longer the user's current one is rejected (see SessionFreshnessInterceptor)
 */
public record AuthPrincipal(UUID userId, UUID coachId, UserRole role, long passwordEpoch) {
}
