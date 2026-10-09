package com.coachplatform.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window limiter of FAILED attempts per key (IP, email...). In memory: valid for a single instance;
 * with several instances it must move to a shared store.
 */
public final class AttemptLimiter {

    private static final int SWEEP_THRESHOLD = 10_000;

    private final Clock clock;
    private final int maxFailures;
    private final Duration window;
    private final ConcurrentHashMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public AttemptLimiter(Clock clock, int maxFailures, Duration window) {
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.window = window;
    }

    public boolean isBlocked(String key) {
        Deque<Instant> attempts = failures.get(key);
        if (attempts == null) {
            return false;
        }
        synchronized (attempts) {
            prune(attempts);
            return attempts.size() >= maxFailures;
        }
    }

    public void recordFailure(String key) {
        if (failures.size() > SWEEP_THRESHOLD) {
            sweep();
        }
        Deque<Instant> attempts = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (attempts) {
            prune(attempts);
            attempts.addLast(clock.instant());
        }
    }

    public void reset(String key) {
        failures.remove(key);
    }

    /** Forgets every key that starts with the prefix (e.g. all "email|ip" pairs of one email). */
    public void resetWithPrefix(String prefix) {
        failures.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Time until the oldest counted failure leaves the window (at least 1 second). */
    public Duration retryAfter(String key) {
        Deque<Instant> attempts = failures.get(key);
        if (attempts == null) {
            return Duration.ofSeconds(1);
        }
        synchronized (attempts) {
            prune(attempts);
            if (attempts.isEmpty()) {
                return Duration.ofSeconds(1);
            }
            Duration wait = Duration.between(clock.instant(), attempts.peekFirst().plus(window));
            return wait.isNegative() || wait.isZero() ? Duration.ofSeconds(1) : wait;
        }
    }

    private void prune(Deque<Instant> attempts) {
        Instant cutoff = clock.instant().minus(window);
        while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(cutoff)) {
            attempts.removeFirst();
        }
    }

    private void sweep() {
        failures.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue());
                return e.getValue().isEmpty();
            }
        });
    }
}
