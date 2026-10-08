package com.coachplatform.common;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Repeats an operation that failed with {@link ConcurrentChangeException}, at most {@value #MAX_ATTEMPTS} attempts in total,
 * with a short pause between them. Only that exception is retried; business rejections and every other error go straight up.
 *
 * <p>The action MUST open its own transaction on every attempt (a rolled-back transaction cannot be reused), so callers wrap
 * the transactional work inside the action instead of annotating the calling method with {@code @Transactional}.
 */
public final class ConflictRetry {

    public static final int MAX_ATTEMPTS = 3;

    private ConflictRetry() {
    }

    public static <T> T run(Supplier<T> action) {
        return run(action, ConflictRetry::pause);
    }

    /** @param pauseBeforeRetry called with the number of the attempt that just failed (1, 2...); injectable so tests need not sleep */
    static <T> T run(Supplier<T> action, IntConsumer pauseBeforeRetry) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (ConcurrentChangeException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                pauseBeforeRetry.accept(attempt);
            }
        }
    }

    /** A minimal, slightly randomised pause (10-20 ms, then 20-30 ms) so two colliding requests do not retry in lockstep. */
    private static void pause(int failedAttempt) {
        try {
            Thread.sleep(10L * failedAttempt + ThreadLocalRandom.current().nextInt(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying a conflicting change", e);
        }
    }
}
