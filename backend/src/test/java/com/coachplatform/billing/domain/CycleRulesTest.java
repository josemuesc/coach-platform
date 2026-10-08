package com.coachplatform.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coachplatform.billing.api.CycleStatus;
import com.coachplatform.billing.domain.CycleRuleException.Code;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CycleRulesTest {

    /** Builds rules whose "today" (Bogota) is the given date, at noon local time. */
    private static CycleRules rulesOn(String bogotaDate) {
        Instant noonBogota = LocalDate.parse(bogotaDate).atTime(12, 0).atZone(CycleCalendar.BOGOTA).toInstant();
        return new CycleRules(new CycleCalendar(Clock.fixed(noonBogota, ZoneOffset.UTC), CycleCalendar.BOGOTA));
    }

    private static CycleState active(String start, String end, int included, int used) {
        return new CycleState(LocalDate.parse(start), LocalDate.parse(end), LocalDate.parse(end), included, used, CycleStatus.ACTIVE, null);
    }

    private static CycleRuleException.Code codeOf(Runnable action) {
        try {
            action.run();
        } catch (CycleRuleException e) {
            return e.code();
        }
        throw new AssertionError("expected a CycleRuleException");
    }

    // ---- opening a cycle ------------------------------------------------------------------------

    @Test
    void firstPaymentOpensACycleStartingTodayAndEndingOneMonthLater() {
        var result = rulesOn("2026-10-06").openCycle(null, 8, Optional.empty());

        assertThat(result.newCycle().startDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(result.newCycle().endDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(result.newCycle().classesIncluded()).isEqualTo(8);
        assertThat(result.newCycle().classesUsed()).isZero();
        assertThat(result.newCycle().status()).isEqualTo(CycleStatus.ACTIVE);
        assertThat(result.previousUpdated()).isEmpty();
    }

    @Test
    void paymentOnDay31UsesTheLastDayOfAShorterMonth() {
        var result = rulesOn("2026-01-31").openCycle(null, 12, Optional.empty());
        assertThat(result.newCycle().endDate()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void paymentIsBlockedWhileTheActiveCycleHasNotReachedItsDeadline() {
        var previous = Optional.of(active("2026-10-06", "2026-11-06", 8, 3));

        assertThat(codeOf(() -> rulesOn("2026-10-20").openCycle(null, 8, previous))).isEqualTo(Code.ACTIVE_CYCLE_EXISTS);
        assertThat(codeOf(() -> rulesOn("2026-11-05").openCycle(null, 8, previous))).isEqualTo(Code.ACTIVE_CYCLE_EXISTS);
    }

    @Test
    void renewalOnTheDeadlineDayClosesTheOldCycleAsExpiredAndOpensTheNewOne() {
        var previous = active("2026-10-06", "2026-11-06", 8, 5);

        var result = rulesOn("2026-11-06").openCycle(null, 8, Optional.of(previous));

        assertThat(result.previousUpdated()).hasValueSatisfying(p -> {
            assertThat(p.status()).isEqualTo(CycleStatus.EXPIRED);
            assertThat(p.classesLost()).isEqualTo(3);
        });
        assertThat(result.newCycle().startDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(result.newCycle().classesUsed()).isZero(); // no carry-over
    }

    @Test
    void renewalOnTheDeadlineDayCannotBeBackdatedBeforeTheDeadline() {
        var previous = active("2026-10-06", "2026-11-06", 8, 5);

        assertThat(codeOf(() -> rulesOn("2026-11-06").openCycle(LocalDate.of(2026, 11, 4), 8, Optional.of(previous))))
                .isEqualTo(Code.INVALID_PAYMENT_DATE);
    }

    @Test
    void latePaymentStartsTheNewCycleOnThePaymentDayLeavingDaysWithoutCycle() {
        // Cycle ended Nov 6 with classes unused; the student pays on Nov 12.
        var previous = active("2026-10-06", "2026-11-06", 8, 5);

        var result = rulesOn("2026-11-12").openCycle(null, 8, Optional.of(previous));

        assertThat(result.newCycle().startDate()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(result.newCycle().endDate()).isEqualTo(LocalDate.of(2026, 12, 12));
        // The stale ACTIVE cycle is closed lazily as part of the same operation.
        assertThat(result.previousUpdated()).hasValueSatisfying(p -> assertThat(p.status()).isEqualTo(CycleStatus.EXPIRED));
    }

    @Test
    void studentWhoCompletedEarlyCanRenewImmediately() {
        var completed = new CycleState(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 6), 8, 8,
                CycleStatus.COMPLETED, LocalDate.of(2026, 10, 28));

        var result = rulesOn("2026-10-29").openCycle(null, 8, Optional.of(completed));

        assertThat(result.newCycle().startDate()).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(result.previousUpdated()).isEmpty();
    }

    @Test
    void cannotBackdateBeforeThePreviousCycleEnded() {
        var completed = new CycleState(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 6), 8, 8,
                CycleStatus.COMPLETED, LocalDate.of(2026, 10, 28));

        assertThat(codeOf(() -> rulesOn("2026-10-29").openCycle(LocalDate.of(2026, 10, 27), 8, Optional.of(completed))))
                .isEqualTo(Code.INVALID_PAYMENT_DATE);
    }

    @Test
    void paymentDateMustNotBeFutureNorMoreThanThreeDaysBack() {
        var rules = rulesOn("2026-10-06");

        assertThat(codeOf(() -> rules.openCycle(LocalDate.of(2026, 10, 7), 8, Optional.empty()))).isEqualTo(Code.INVALID_PAYMENT_DATE);
        assertThat(codeOf(() -> rules.openCycle(LocalDate.of(2026, 10, 2), 8, Optional.empty()))).isEqualTo(Code.INVALID_PAYMENT_DATE);

        var threeDaysBack = rules.openCycle(LocalDate.of(2026, 10, 3), 8, Optional.empty());
        assertThat(threeDaysBack.newCycle().startDate()).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(threeDaysBack.newCycle().endDate()).isEqualTo(LocalDate.of(2026, 11, 3));
    }

    @Test
    void todayIsTheBogotaDayEvenWhenUtcAlreadyRolledOver() {
        // 03:00 UTC Oct 7 = Oct 6 22:00 in Bogota: a payment "today" starts on Oct 6, and Oct 7 is still the future.
        var rules = new CycleRules(new CycleCalendar(
                Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"), ZoneOffset.UTC), CycleCalendar.BOGOTA));

        assertThat(rules.openCycle(null, 8, Optional.empty()).newCycle().startDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(codeOf(() -> rules.openCycle(LocalDate.of(2026, 10, 7), 8, Optional.empty()))).isEqualTo(Code.INVALID_PAYMENT_DATE);
    }

    @Test
    void aPlanWithoutClassesIsRejected() {
        assertThat(codeOf(() -> rulesOn("2026-10-06").openCycle(null, 0, Optional.empty()))).isEqualTo(Code.INVALID_PLAN);
    }

    // ---- closing: completed vs expired ----------------------------------------------------------

    @Test
    void usingTheLastClassCompletesTheCycleBeforeTheDeadline() {
        var rules = rulesOn("2026-10-28");
        var almostDone = active("2026-10-06", "2026-11-06", 8, 7);

        var done = rules.consumeClass(almostDone);

        assertThat(done.status()).isEqualTo(CycleStatus.COMPLETED);
        assertThat(done.classesUsed()).isEqualTo(8);
        assertThat(done.completedOn()).isEqualTo(LocalDate.of(2026, 10, 28));
        assertThat(done.classesLost()).isZero();
    }

    @Test
    void consumingANormalClassKeepsTheCycleActive() {
        var next = rulesOn("2026-10-10").consumeClass(active("2026-10-06", "2026-11-06", 8, 2));
        assertThat(next.status()).isEqualTo(CycleStatus.ACTIVE);
        assertThat(next.classesRemaining()).isEqualTo(5);
    }

    @Test
    void cycleExpiresAfterTheDeadlineWithUnusedClassesAndTheLeftoversAreLost() {
        var rules = rulesOn("2026-11-07");

        var evaluated = rules.evaluate(active("2026-10-06", "2026-11-06", 8, 5));

        assertThat(evaluated.status()).isEqualTo(CycleStatus.EXPIRED);
        assertThat(evaluated.classesLost()).isEqualTo(3);
    }

    @Test
    void theDeadlineDayItselfIsStillUsable() {
        var evaluated = rulesOn("2026-11-06").evaluate(active("2026-10-06", "2026-11-06", 8, 5));
        assertThat(evaluated.status()).isEqualTo(CycleStatus.ACTIVE);
    }

    @Test
    void completedWinsOverExpiredWhenBothApply() {
        var evaluated = rulesOn("2026-11-20").evaluate(active("2026-10-06", "2026-11-06", 8, 8));
        assertThat(evaluated.status()).isEqualTo(CycleStatus.COMPLETED);
    }

    @Test
    void closedCyclesDoNotChangeWhenEvaluated() {
        var expired = active("2026-10-06", "2026-11-06", 8, 5);
        var closed = rulesOn("2026-11-20").evaluate(expired);
        assertThat(rulesOn("2027-05-01").evaluate(closed)).isEqualTo(closed);
    }

    @Test
    void anExpiredOrCompletedCycleCannotConsumeClasses() {
        assertThat(codeOf(() -> rulesOn("2026-11-07").consumeClass(active("2026-10-06", "2026-11-06", 8, 5))))
                .isEqualTo(Code.CYCLE_NOT_ACTIVE);
    }

    // ---- extension by the coach -----------------------------------------------------------------

    @Test
    void coachExtendsTheDeadlineAndWhoAndWhenAreRecorded() {
        var rules = rulesOn("2026-11-03");
        UUID coachUser = UUID.randomUUID();

        var result = rules.extend(active("2026-10-06", "2026-11-06", 8, 6), LocalDate.of(2026, 11, 13), coachUser, 60);

        assertThat(result.cycle().endDate()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(result.record().previousEndDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(result.record().newEndDate()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(result.record().extendedBy()).isEqualTo(coachUser);
        assertThat(result.record().extendedAt()).isEqualTo(Instant.parse("2026-11-03T17:00:00Z")); // noon Bogota
    }

    @Test
    void extensionMustMoveTheDeadlineForward() {
        var cycle = active("2026-10-06", "2026-11-06", 8, 6);
        var rules = rulesOn("2026-11-03");

        assertThat(codeOf(() -> rules.extend(cycle, LocalDate.of(2026, 11, 6), UUID.randomUUID(), 60))).isEqualTo(Code.INVALID_EXTENSION);
        assertThat(codeOf(() -> rules.extend(cycle, LocalDate.of(2026, 11, 1), UUID.randomUUID(), 60))).isEqualTo(Code.INVALID_EXTENSION);
    }

    // ---- reopening an expired cycle -----------------------------------------------------------------

    private static CycleState expiredByDate() {
        return active("2026-10-06", "2026-11-06", 8, 5);   // seen on Nov 9: three days past its deadline
    }

    @Test
    void anExpiredCycleCanBeReopenedByGivingItADeadlineOfTodayOrLater() {
        UUID coach = UUID.randomUUID();
        var result = rulesOn("2026-11-09").extend(expiredByDate(), LocalDate.of(2026, 11, 20), coach, 60, 0, false);

        assertThat(result.cycle().status()).isEqualTo(CycleStatus.ACTIVE);
        assertThat(result.cycle().endDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(result.cycle().originalEndDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(result.cycle().classesUsed()).isEqualTo(5);   // nothing is added or lost
        assertThat(result.record().reopened()).isTrue();
        assertThat(result.record().extendedBy()).isEqualTo(coach);
        assertThat(result.record().previousEndDate()).isEqualTo(LocalDate.of(2026, 11, 6));
    }

    @Test
    void aReopenedDeadlineInThePastWouldExpireAtOnceSoItIsRejected() {
        var cycle = expiredByDate();
        assertThat(codeOf(() -> rulesOn("2026-11-09").extend(cycle, LocalDate.of(2026, 11, 8), UUID.randomUUID(), 60, 0, false)))
                .isEqualTo(Code.INVALID_EXTENSION);
        // today itself is fine
        assertThat(rulesOn("2026-11-09").extend(cycle, LocalDate.of(2026, 11, 9), UUID.randomUUID(), 60, 0, false).cycle().isActive()).isTrue();
    }

    @Test
    void reopeningIsAlsoLimitedByTheCapOverTheOriginalDeadline() {
        var cycle = expiredByDate();
        assertThat(codeOf(() -> rulesOn("2026-11-09").extend(cycle, LocalDate.of(2027, 1, 6), UUID.randomUUID(), 60, 0, false)))
                .isEqualTo(Code.EXTENSION_LIMIT_EXCEEDED);
    }

    @Test
    void aCompletedCycleCanNeverBeReopenedOrExtended() {
        var completed = new CycleState(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 6), 8, 8,
                CycleStatus.COMPLETED, LocalDate.of(2026, 10, 28));
        assertThat(codeOf(() -> rulesOn("2026-11-09").extend(completed, LocalDate.of(2026, 11, 20), UUID.randomUUID(), 60, 0, false)))
                .isEqualTo(Code.REOPEN_NOT_ALLOWED);
        // all classes used but still stored as ACTIVE: it is effectively completed, same answer
        assertThat(codeOf(() -> rulesOn("2026-11-03").extend(active("2026-10-06", "2026-11-06", 8, 8), LocalDate.of(2026, 11, 20), UUID.randomUUID(), 60, 0, false)))
                .isEqualTo(Code.REOPEN_NOT_ALLOWED);
    }

    @Test
    void anExpiredCycleCannotBeReopenedOnceTheStudentHasANewerCycle() {
        assertThat(codeOf(() -> rulesOn("2026-11-09").extend(expiredByDate(), LocalDate.of(2026, 11, 20), UUID.randomUUID(), 60, 0, true)))
                .isEqualTo(Code.REOPEN_NOT_ALLOWED);
    }

    // ---- classes still to be marked hold the cycle open ---------------------------------------------

    @Test
    void aCyclePastItsDeadlineDoesNotExpireWhileStartedClassesAreUnmarked() {
        var overdue = active("2026-10-06", "2026-11-06", 8, 5);
        assertThat(rulesOn("2026-11-09").evaluate(overdue, 0).status()).isEqualTo(CycleStatus.EXPIRED);
        assertThat(rulesOn("2026-11-09").evaluate(overdue, 2).status()).isEqualTo(CycleStatus.ACTIVE);
    }

    @Test
    void anOverdueCycleWithPendingMarksStillTakesTheMarks() {
        var next = rulesOn("2026-11-09").consumeClass(active("2026-10-06", "2026-11-06", 8, 5), 1);
        assertThat(next.classesUsed()).isEqualTo(6);
        assertThat(next.status()).isEqualTo(CycleStatus.ACTIVE);
        // without a pending mark it has simply expired
        assertThat(codeOf(() -> rulesOn("2026-11-09").consumeClass(active("2026-10-06", "2026-11-06", 8, 5), 0)))
                .isEqualTo(Code.CYCLE_NOT_ACTIVE);
        // marking the last class of an overdue cycle completes it
        assertThat(rulesOn("2026-11-09").consumeClass(active("2026-10-06", "2026-11-06", 8, 7), 1).status()).isEqualTo(CycleStatus.COMPLETED);
    }

    @Test
    void renewalIsBlockedWhileTheOldCycleHasUnmarkedClasses() {
        var previous = Optional.of(active("2026-10-06", "2026-11-06", 8, 5));

        // on the deadline day and after it (late payer): blocked until the coach marks them
        assertThat(codeOf(() -> rulesOn("2026-11-06").openCycle(null, 8, previous, 2))).isEqualTo(Code.PENDING_SESSIONS_TO_MARK);
        assertThat(codeOf(() -> rulesOn("2026-11-12").openCycle(null, 8, previous, 1))).isEqualTo(Code.PENDING_SESSIONS_TO_MARK);
        // before the deadline it is simply "still active"
        assertThat(codeOf(() -> rulesOn("2026-11-02").openCycle(null, 8, previous, 1))).isEqualTo(Code.ACTIVE_CYCLE_EXISTS);
        // once resolved, the renewal goes through
        assertThat(rulesOn("2026-11-12").openCycle(null, 8, previous, 0).newCycle().startDate()).isEqualTo(LocalDate.of(2026, 11, 12));
    }

    @Test
    void extendingDoesNotTouchTheClassCounters() {
        var result = rulesOn("2026-11-03").extend(active("2026-10-06", "2026-11-06", 8, 6), LocalDate.of(2026, 11, 13), UUID.randomUUID(), 60);
        assertThat(result.cycle().classesUsed()).isEqualTo(6);
        assertThat(result.cycle().status()).isEqualTo(CycleStatus.ACTIVE);
    }

    // ---- extension cap ----------------------------------------------------------------------------

    @Test
    void extensionMayReachExactlySixtyDaysPastTheOriginalDeadline() {
        // original deadline Nov 6 + 60 days = Jan 5
        var result = rulesOn("2026-11-03").extend(active("2026-10-06", "2026-11-06", 8, 6), LocalDate.of(2027, 1, 5), UUID.randomUUID(), 60);
        assertThat(result.cycle().endDate()).isEqualTo(LocalDate.of(2027, 1, 5));
    }

    @Test
    void extensionBeyondSixtyDaysPastTheOriginalDeadlineIsRejected() {
        var cycle = active("2026-10-06", "2026-11-06", 8, 6);
        assertThat(codeOf(() -> rulesOn("2026-11-03").extend(cycle, LocalDate.of(2027, 1, 6), UUID.randomUUID(), 60)))
                .isEqualTo(Code.EXTENSION_LIMIT_EXCEEDED);
    }

    @Test
    void theCapIsCumulativeOverTheOriginalDeadlineNotPerExtension() {
        var rules = rulesOn("2026-11-03");
        UUID coach = UUID.randomUUID();
        var first = rules.extend(active("2026-10-06", "2026-11-06", 8, 6), LocalDate.of(2026, 12, 20), coach, 60).cycle(); // +44 days
        assertThat(first.originalEndDate()).isEqualTo(LocalDate.of(2026, 11, 6));   // the original is kept

        // +44 more days relative to the CURRENT deadline would be fine per extension, but it is 88 over the original
        assertThat(codeOf(() -> rules.extend(first, LocalDate.of(2027, 2, 2), coach, 60))).isEqualTo(Code.EXTENSION_LIMIT_EXCEEDED);
        // up to the remaining 16 days it is allowed
        assertThat(rules.extend(first, LocalDate.of(2027, 1, 5), coach, 60).cycle().endDate()).isEqualTo(LocalDate.of(2027, 1, 5));
    }

    @Test
    void theCapIsConfigurable() {
        var cycle = active("2026-10-06", "2026-11-06", 8, 6);
        var rules = rulesOn("2026-11-03");

        assertThat(rules.extend(cycle, LocalDate.of(2026, 11, 16), UUID.randomUUID(), 10).cycle().endDate()).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(codeOf(() -> rules.extend(cycle, LocalDate.of(2026, 11, 17), UUID.randomUUID(), 10))).isEqualTo(Code.EXTENSION_LIMIT_EXCEEDED);
        assertThat(codeOf(() -> rules.extend(cycle, LocalDate.of(2026, 11, 7), UUID.randomUUID(), 0))).isEqualTo(Code.EXTENSION_LIMIT_EXCEEDED); // extensions disabled
    }

    @Test
    void aCycleOpenedByAPaymentRemembersItsOriginalDeadline() {
        var created = rulesOn("2026-10-06").openCycle(null, 8, Optional.empty()).newCycle();
        assertThat(created.originalEndDate()).isEqualTo(created.endDate()).isEqualTo(LocalDate.of(2026, 11, 6));
    }

    @Test
    void domainRecordRejectsInconsistentCounters() {
        assertThatThrownBy(() -> active("2026-10-06", "2026-11-06", 8, 9)).isInstanceOf(IllegalArgumentException.class);
    }
}
