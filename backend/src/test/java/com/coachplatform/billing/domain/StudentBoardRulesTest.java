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

    @Test
    void aStudentWithPlentyOfTimeAndClassesIsUpToDate() {
        var c = classify(true, true, activeCycle(5, "2026-11-06"));
        assertThat(c.status()).isEqualTo(BoardStatus.AL_DIA);
        assertThat(c.expiringSoon()).isFalse();
        assertThat(c.expiringBy()).isNull();
        assertThat(c.daysUntilEnd()).isEqualTo(28);
    }

    @Test
    void fewDaysFewClassesOrBothAreToldApart() {
        assertThat(classify(true, true, activeCycle(6, "2026-10-12")).expiringBy()).isEqualTo(ExpiringBy.DAYS);
        assertThat(classify(true, true, activeCycle(1, "2026-11-06")).expiringBy()).isEqualTo(ExpiringBy.CLASSES);
        assertThat(classify(true, true, activeCycle(1, "2026-10-12")).expiringBy()).isEqualTo(ExpiringBy.BOTH);
        assertThat(classify(true, true, activeCycle(6, "2026-10-12")).status()).isEqualTo(BoardStatus.POR_VENCER);
    }

    @Test
    void theThresholdsAreInclusive() {
        assertThat(classify(true, true, activeCycle(6, "2026-10-14")).expiringSoon()).as("exactly 5 days").isTrue();
        assertThat(classify(true, true, activeCycle(6, "2026-10-15")).expiringSoon()).as("6 days").isFalse();
        assertThat(classify(true, true, activeCycle(2, "2026-11-06")).expiringSoon()).as("2 classes").isFalse();
        assertThat(classify(true, true, activeCycle(6, "2026-10-09")).daysUntilEnd()).as("ends today").isZero();
    }

    @Test
    void aCompletedCycleIsAboutToExpireByClassesNotUpToDate() {
        var c = classify(true, true, new LatestCycle(CycleStatus.COMPLETED, 0, LocalDate.parse("2026-10-30")));
        assertThat(c.status()).isEqualTo(BoardStatus.POR_VENCER);
        assertThat(c.expiringSoon()).isTrue();
        assertThat(c.expiringBy()).isEqualTo(ExpiringBy.CLASSES);
        assertThat(c.noPlan()).isFalse();
        assertThat(c.daysUntilEnd()).isNull();
    }

    @Test
    void noCycleOrAnExpiredOneIsNoPlan() {
        assertThat(classify(true, true, null).status()).isEqualTo(BoardStatus.SIN_PLAN);
        assertThat(classify(true, true, null).noPlan()).isTrue();
        var expired = classify(true, true, new LatestCycle(CycleStatus.EXPIRED, 3, LocalDate.parse("2026-10-01")));
        assertThat(expired.status()).isEqualTo(BoardStatus.SIN_PLAN);
        assertThat(expired.expiringSoon()).isFalse();
    }

    @Test
    void thePrecedenceIsSuspendedThenNotActivatedThenNoPlanThenAboutToExpire() {
        var soon = activeCycle(1, "2026-10-10");
        assertThat(classify(false, false, soon).status()).isEqualTo(BoardStatus.SUSPENDIDO);
        assertThat(classify(true, false, soon).status()).isEqualTo(BoardStatus.SIN_ACTIVAR);
        assertThat(classify(true, false, null).status()).isEqualTo(BoardStatus.SIN_ACTIVAR);
        // not activated, but the filter flags still tell the truth
        assertThat(classify(true, false, soon).expiringSoon()).isTrue();
        assertThat(classify(true, false, null).noPlan()).isTrue();
    }

    @Test
    void aSuspendedStudentBelongsToNoBucket() {
        var c = classify(false, true, activeCycle(1, "2026-10-10"));
        assertThat(c.expiringSoon()).isFalse();
        assertThat(c.noPlan()).isFalse();
        assertThat(c.expiringBy()).isNull();
        assertThat(classify(false, true, null).noPlan()).isFalse();
    }
}
