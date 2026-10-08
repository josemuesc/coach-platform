package com.coachplatform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ConflictRetryTest {

    @Test
    void anOperationThatWorksRunsOnceAndNeverPauses() {
        AtomicInteger calls = new AtomicInteger();
        List<Integer> pauses = new ArrayList<>();

        String result = ConflictRetry.run(() -> {
            calls.incrementAndGet();
            return "ok";
        }, pauses::add);

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(1);
        assertThat(pauses).isEmpty();
    }

    @Test
    void aConflictIsRetriedAndTheSecondOrThirdAttemptCanSucceed() {
        for (int failures = 1; failures <= 2; failures++) {
            AtomicInteger calls = new AtomicInteger();
            List<Integer> pauses = new ArrayList<>();
            int failuresBeforeSuccess = failures;

            String result = ConflictRetry.run(() -> {
                if (calls.incrementAndGet() <= failuresBeforeSuccess) {
                    throw new ConcurrentChangeException();
                }
                return "ok";
            }, pauses::add);

            assertThat(result).isEqualTo("ok");
            assertThat(calls).as("attempts").hasValue(failures + 1);
            assertThat(pauses).as("a pause after each failed attempt").hasSize(failures);
        }
    }

    @Test
    void afterThreeAttemptsInTotalTheConflictReachesTheCaller() {
        AtomicInteger calls = new AtomicInteger();
        List<Integer> pauses = new ArrayList<>();

        assertThatThrownBy(() -> ConflictRetry.run(() -> {
            calls.incrementAndGet();
            throw new ConcurrentChangeException();
        }, pauses::add)).isInstanceOf(ConcurrentChangeException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((ApiException) e).code()).isEqualTo("CONCURRENT_CHANGE");
                });

        assertThat(calls).as("max 3 attempts").hasValue(3);
        assertThat(ConflictRetry.MAX_ATTEMPTS).isEqualTo(3);
        assertThat(pauses).as("no pause after the last attempt").containsExactly(1, 2);
    }

    @Test
    void onlyConflictsAreRetriedEveryOtherErrorGoesStraightUp() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> ConflictRetry.run(() -> {
            calls.incrementAndGet();
            throw new ApiException(HttpStatus.CONFLICT, "SLOT_TAKEN");   // a business rejection: repeating it would change nothing
        }, n -> { })).isInstanceOf(ApiException.class).hasMessage("SLOT_TAKEN");
        assertThat(calls).hasValue(1);

        calls.set(0);
        assertThatThrownBy(() -> ConflictRetry.run(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }, n -> { })).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void theRealPauseIsMinimal() {
        AtomicInteger calls = new AtomicInteger();
        long started = System.nanoTime();

        assertThatThrownBy(() -> ConflictRetry.run(() -> {
            calls.incrementAndGet();
            throw new ConcurrentChangeException();
        })).isInstanceOf(ConcurrentChangeException.class);

        long millis = (System.nanoTime() - started) / 1_000_000;
        assertThat(calls).hasValue(3);
        assertThat(millis).as("two pauses of 10-30 ms").isBetween(20L, 400L);
    }
}
