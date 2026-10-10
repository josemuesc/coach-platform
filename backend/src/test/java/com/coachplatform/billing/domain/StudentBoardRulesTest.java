package com.coachplatform.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.billing.api.BoardStatus;
import com.coachplatform.billing.api.CycleStatus;
import com.coachplatform.billing.api.ExpiringBy;
import com.coachplatform.billing.domain.StudentBoardRules.Classification;
import com.coachplatform.billing.domain.StudentBoardRules.LatestCycle;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class StudentBoardRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);

    private static Classification classify(boolean active, boolean account, LatestCycle latest) {
        return StudentBoardRules.classify(active, account, latest, TODAY, 5, 1);
    }

    private static LatestCycle activeCycle(int remaining, String end) {
        return new LatestCycle(CycleStatus.ACTIVE, remaining, LocalDate.parse(end));
    }

    private static LatestCycle completed() {
        return new LatestCycle(CycleStatus.COMPLETED, 0, LocalDate.parse("2026-10-30"));
    }

    private static LatestCycle expired() {
        return new LatestCycle(CycleStatus.EXPIRED, 3, LocalDate.parse("2026-10-01"));
    }

    @Test
    void anActiveStudentWithPlentyOfTimeAndClassesIsUpToDate() {
        var c = classify(true, true, activeCycle(5, "2026-11-06"));
        assertThat(c.status()).isEqualTo(BoardStatus.AL_DIA);
        assertThat(c.activeCycle()).isTrue();
        assertThat(c.expiringSoon()).isFalse();
        assertThat(c.needsRenewal()).isFalse();
        assertThat(c.expiringBy()).isNull();
        assertThat(c.daysUntilEnd()).isEqualTo(28);
    }

    @Test
    void fewDaysFewClassesOrBothAreToldApartAndAreSubsetsOfActive() {
        for (var cycle : new LatestCycle[] {activeCycle(6, "2026-10-12"), activeCycle(1, "2026-11-06"), activeCycle(1, "2026-10-12")}) {
            var c = classify(true, true, cycle);
            assertThat(c.activeCycle()).isTrue();
            assertThat(c.expiringSoon()).isTrue();
            assertThat(c.status()).isEqualTo(BoardStatus.POR_VENCER);
        }
        assertThat(classify(true, true, activeCycle(6, "2026-10-12")).expiringBy()).isEqualTo(ExpiringBy.DAYS);
        assertThat(classify(true, true, activeCycle(1, "2026-11-06")).expiringBy()).isEqualTo(ExpiringBy.CLASSES);
        assertThat(classify(true, true, activeCycle(1, "2026-10-12")).expiringBy()).isEqualTo(ExpiringBy.BOTH);
    }

    @Test
    void theThresholdsAreInclusive() {
        assertThat(classify(true, true, activeCycle(6, "2026-10-14")).expiringSoon()).as("exactly 5 days").isTrue();
        assertThat(classify(true, true, activeCycle(6, "2026-10-15")).expiringSoon()).as("6 days").isFalse();
        assertThat(classify(true, true, activeCycle(2, "2026-11-06")).expiringSoon()).as("2 classes").isFalse();
        assertThat(classify(true, true, activeCycle(6, "2026-10-09")).daysUntilEnd()).as("ends today").isZero();
    }

    @Test
    void aCompletedCycleIsNotActiveItIsSinClasesAndNeedsRenewal() {
        var c = classify(true, true, completed());
        assertThat(c.status()).isEqualTo(BoardStatus.SIN_CLASES);
        assertThat(c.activeCycle()).isFalse();
        assertThat(c.expiringSoon()).as("expiring is a subset of active").isFalse();
        assertThat(c.expiringBy()).isNull();
        assertThat(c.needsRenewal()).isTrue();
        assertThat(c.daysUntilEnd()).isNull();
    }

    @Test
    void anExpiredCycleIsVencidoAndNeedsRenewal() {
        var c = classify(true, true, expired());
        assertThat(c.status()).isEqualTo(BoardStatus.VENCIDO);
        assertThat(c.activeCycle()).isFalse();
        assertThat(c.needsRenewal()).isTrue();
        assertThat(c.expiringSoon()).isFalse();
    }

    @Test
    void neverHavingPaidIsSinPlanInactiveAndNotAThingToRenew() {
        var c = classify(true, true, null);
        assertThat(c.status()).isEqualTo(BoardStatus.SIN_PLAN);
        assertThat(c.activeCycle()).isFalse();
        assertThat(c.needsRenewal()).isFalse();
        assertThat(c.daysUntilEnd()).isNull();
    }

    @Test
    void thePrecedenceIsSuspendedThenNotActivatedThenTheCycle() {
        var soon = activeCycle(1, "2026-10-10");
        assertThat(classify(false, false, soon).status()).isEqualTo(BoardStatus.SUSPENDIDO);
        assertThat(classify(true, false, soon).status()).isEqualTo(BoardStatus.SIN_ACTIVAR);
        assertThat(classify(true, false, null).status()).isEqualTo(BoardStatus.SIN_ACTIVAR);
        assertThat(classify(true, false, completed()).status()).isEqualTo(BoardStatus.SIN_ACTIVAR);
        // the chip hides the cycle, the flags do not
        assertThat(classify(true, false, soon).activeCycle()).isTrue();
        assertThat(classify(true, false, soon).expiringSoon()).isTrue();
        assertThat(classify(true, false, completed()).needsRenewal()).isTrue();
    }

    @Test
    void aSuspendedStudentIsInactiveAndBelongsToNoOtherBucket() {
        for (var cycle : new LatestCycle[] {activeCycle(1, "2026-10-10"), completed(), expired(), null}) {
            var c = classify(false, true, cycle);
            assertThat(c.status()).isEqualTo(BoardStatus.SUSPENDIDO);
            assertThat(c.activeCycle()).isFalse();
            assertThat(c.expiringSoon()).isFalse();
            assertThat(c.needsRenewal()).isFalse();
            assertThat(c.expiringBy()).isNull();
        }
    }
}
