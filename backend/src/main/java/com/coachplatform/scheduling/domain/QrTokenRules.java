package com.coachplatform.scheduling.domain;

import static com.coachplatform.scheduling.domain.SchedulingRuleException.Code.INVALID_QR;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The rotating code a coach shows for an event. Stateless: {@code token = base64url(eventId || window || HMAC-SHA256(secret,
 * eventId || window))} with {@code window = epochSeconds / 30}. The window in force and the previous one are accepted, so a
 * code lives between 30 and 60 seconds. Any malformed, forged, stale or future token fails with the same {@code INVALID_QR}.
 *
 * <p>The code proves the student saw a code shown by the coach in the last minute; it cannot prove physical presence (a photo
 * sent within that minute would work). The audit trail records every scan.
 */
public final class QrTokenRules {

    public static final long WINDOW_SECONDS = 30;
    private static final int ID_BYTES = 16;
    private static final int WINDOW_BYTES = Long.BYTES;
    private static final int MAC_BYTES = 32;
    private static final byte[] CONTEXT = "coach-platform/qr/v1".getBytes(StandardCharsets.US_ASCII);
    private static final String INVALID = "The code is not valid or has expired";

    /** The token, how many seconds it will still be accepted for, and the instant it stops being accepted. */
    public record IssuedToken(String token, long validForSeconds, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedToken[token=<redacted>, validForSeconds=" + validForSeconds + ", expiresAt=" + expiresAt + "]";
        }
    }

    private final Clock clock;
    private final byte[] secret;

    public QrTokenRules(Clock clock, byte[] secret) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException("The QR secret must be at least 32 bytes");
        }
        this.clock = clock;
        this.secret = secret.clone();
    }

    public IssuedToken issue(UUID eventId) {
        long now = clock.instant().getEpochSecond();
        long window = now / WINDOW_SECONDS;
        long expiresAtSecond = (window + 2) * WINDOW_SECONDS;   // accepted through the end of the next window
        byte[] mac = mac(eventId, window);
        ByteBuffer raw = ByteBuffer.allocate(ID_BYTES + WINDOW_BYTES + MAC_BYTES);
        raw.putLong(eventId.getMostSignificantBits()).putLong(eventId.getLeastSignificantBits()).putLong(window).put(mac);
        return new IssuedToken(Base64.getUrlEncoder().withoutPadding().encodeToString(raw.array()),
                expiresAtSecond - now, Instant.ofEpochSecond(expiresAtSecond));
    }

    /** @return the event the code was issued for; throws {@code INVALID_QR} for anything that is not a live, genuine code */
    public UUID verify(String token) {
        byte[] raw;
        try {
            raw = token == null ? null : Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            raw = null;
        }
        if (raw == null || raw.length != ID_BYTES + WINDOW_BYTES + MAC_BYTES) {
            throw invalid();
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        UUID eventId = new UUID(buffer.getLong(), buffer.getLong());
        long window = buffer.getLong();
        byte[] given = Arrays.copyOfRange(raw, ID_BYTES + WINDOW_BYTES, raw.length);
        boolean genuine = MessageDigest.isEqual(given, mac(eventId, window));
        long current = clock.instant().getEpochSecond() / WINDOW_SECONDS;
        boolean live = window == current || window == current - 1;
        if (!genuine || !live) {
            throw invalid();
        }
        return eventId;
    }

    private byte[] mac(UUID eventId, long window) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret, "HmacSHA256"));
            hmac.update(CONTEXT);
            hmac.update(ByteBuffer.allocate(ID_BYTES + WINDOW_BYTES)
                    .putLong(eventId.getMostSignificantBits()).putLong(eventId.getLeastSignificantBits()).putLong(window).array());
            return hmac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SchedulingRuleException invalid() {
        return new SchedulingRuleException(INVALID_QR, INVALID);
    }
}
