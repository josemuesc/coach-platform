package com.coachplatform.students;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InvitationTokenTest {

    @Test
    void tokensAreLongRandomAndUrlSafe() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = InvitationToken.generate();
            assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+"); // 256 bits in base64url
            assertThat(seen.add(token)).isTrue();
        }
    }

    @Test
    void hashIsDeterministicSha256HexAndDiffersFromTheToken() {
        String hash = InvitationToken.hash("abc");
        assertThat(hash).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(InvitationToken.hash("abc")).isEqualTo(hash);
        assertThat(InvitationToken.hash("abd")).isNotEqualTo(hash);
    }
}
