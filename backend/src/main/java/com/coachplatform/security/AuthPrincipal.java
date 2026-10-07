package com.coachplatform.security;

import java.util.UUID;

public record AuthPrincipal(UUID userId, UUID coachId, UserRole role) {
}
