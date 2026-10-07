package com.coachplatform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private final JwtService jwt = new JwtService(new JwtProperties(SECRET, 60), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void roundTripKeepsTenantAndRole() {
        UUID userId = UUID.randomUUID();
        UUID coachId = UUID.randomUUID();

        AuthPrincipal p = jwt.parse(jwt.issue(userId, coachId, UserRole.COACH)).orElseThrow();

        assertThat(p.userId()).isEqualTo(userId);
        assertThat(p.coachId()).isEqualTo(coachId);
        assertThat(p.role()).isEqualTo(UserRole.COACH);
    }

    @Test
    void rejectsTamperedAndMalformedTokens() {
        String token = jwt.issue(UUID.randomUUID(), UUID.randomUUID(), UserRole.COACH);
        assertThat(jwt.parse(token + "x")).isEmpty();
        assertThat(jwt.parse("garbage")).isEmpty();
    }

    @Test
    void tokenExpiresAfterConfiguredMinutes() {
        String token = jwt.issue(UUID.randomUUID(), UUID.randomUUID(), UserRole.COACH);

        JwtService justBefore = at(NOW.plus(Duration.ofMinutes(59)));
        JwtService justAfter = at(NOW.plus(Duration.ofMinutes(61)));

        assertThat(justBefore.parse(token)).isPresent();
        assertThat(justAfter.parse(token)).isEmpty();
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        JwtService other = new JwtService(new JwtProperties("ffffffffffffffffffffffffffffffff", 60), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(jwt.parse(other.issue(UUID.randomUUID(), UUID.randomUUID(), UserRole.COACH))).isEmpty();
    }

    private static JwtService at(Instant instant) {
        return new JwtService(new JwtProperties(SECRET, 60), Clock.fixed(instant, ZoneOffset.UTC));
    }
}
