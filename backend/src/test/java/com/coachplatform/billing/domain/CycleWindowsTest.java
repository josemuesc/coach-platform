package com.coachplatform.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.billing.api.CycleStatus;
import com.coachplatform.billing.api.PaymentBlockedBy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The flags the profile shows (can the coach register a payment / extend the cycle, and between which dates) are derived from rules that
 * are checked against the ones the server APPLIES: for every state below and every date around the limits, "the window says yes" must
 * be exactly "openCycle / extend accepts".
 */
class CycleWindowsTest {

    private static final String TODAY = "2026-10-09";

    private static CycleRules rules() {
        Instant noon = LocalDate.parse(TODAY).atTime(12, 0).atZone(CycleCalendar.BOGOTA).toInstant();
        return new CycleRules(new CycleCalendar(Clock.fixed(noon, ZoneOffset.UTC), CycleCalendar.BOGOTA));
    }

    private static LocalDate d(String date) {
        return LocalDate.parse(date);
    }

    private static CycleState active(String end, int included, int used) {
        return new CycleState(d("2026-09-09"), d(end), d("2026-10-09").isAfter(d(end)) ? d(end) : d(end), included, used, CycleStatus.ACTIVE, null);
    }

    private static CycleState completed(String end, String completedOn) {
        return new CycleState(d("2026-09-09"), d(end), d(end), 8, 8, CycleStatus.COMPLETED, d(completedOn));
    }

    private static CycleState expired(String end) {
        return new CycleState(d("2026-08-01"), d(end), d(end), 8, 3, CycleStatus.EXPIRED, null);
    }

    private record Scenario(Optional<CycleState> previous, int pending) {
    }

    private static Map<String, Scenario> scenarios() {
        Map<String, Scenario> s = new LinkedHashMap<>();
        s.put("never paid", new Scenario(Optional.empty(), 0));
        s.put("active, deadline in 3 days", new Scenario(Optional.of(active("2026-10-12", 8, 7)), 0));
        s.put("active, deadline in 3 days, one class left", new Scenario(Optional.of(active("2026-10-12", 8, 7)), 1));
        s.put("active, deadline TODAY", new Scenario(Optional.of(active("2026-10-09", 8, 5)), 0));
        s.put("active, deadline today, a class unmarked", new Scenario(Optional.of(active("2026-10-09", 8, 5)), 1));
        s.put("active, deadline passed, a class unmarked (kept open)", new Scenario(Optional.of(active("2026-10-07", 8, 5)), 1));
        s.put("stored ACTIVE but every class used (completes by itself)", new Scenario(Optional.of(active("2026-10-30", 8, 8)), 0));
        s.put("COMPLETED yesterday, deadline in the future", new Scenario(Optional.of(completed("2026-10-30", "2026-10-08")), 0));
        s.put("COMPLETED today", new Scenario(Optional.of(completed("2026-10-30", "2026-10-09")), 0));
        s.put("COMPLETED 10 days ago", new Scenario(Optional.of(completed("2026-10-30", "2026-09-29")), 0));
        s.put("EXPIRED yesterday", new Scenario(Optional.of(expired("2026-10-08")), 0));
        s.put("EXPIRED a month ago", new Scenario(Optional.of(expired("2026-09-01")), 0));
        return s;
    }

    private static boolean openCycleAccepts(CycleRules rules, Scenario s, LocalDate paidOn) {
        try {
            rules.openCycle(paidOn, 8, s.previous(), s.pending());
            return true;
        } catch (CycleRuleException e) {
            return false;
        }
    }

    @Test
    void thePaymentWindowIsExactlyWhatOpenCycleAccepts() {
        CycleRules rules = rules();
        scenarios().forEach((name, s) -> {
            var window = rules.paymentWindow(s.previous(), s.pending());
            for (LocalDate paidOn = d(TODAY).minusDays(8); !paidOn.isAfter(d(TODAY).plusDays(2)); paidOn = paidOn.plusDays(1)) {
                boolean expected = window.allowed() && !paidOn.isBefore(window.paidOnMin()) && !paidOn.isAfter(window.paidOnMax());
                assertThat(openCycleAccepts(rules, s, paidOn)).as(name + " paying on " + paidOn).isEqualTo(expected);
            }
        });
    }

    @Test
    void theBlockedCasesSayWhyAndWhen() {
        CycleRules rules = rules();
        var active = rules.paymentWindow(Optional.of(active("2026-10-12", 8, 7)), 0);
        assertThat(active.allowed()).isFalse();
        assertThat(active.blockedBy()).isEqualTo(PaymentBlockedBy.ACTIVE_CYCLE);
        assertThat(active.opensOn()).isEqualTo(d("2026-10-12"));
        assertThat(active.paidOnMin()).isNull();
        assertThat(active.paidOnMax()).isNull();

        var pending = rules.paymentWindow(Optional.of(active("2026-10-09", 8, 5)), 1);
        assertThat(pending.allowed()).isFalse();
        assertThat(pending.blockedBy()).isEqualTo(PaymentBlockedBy.PENDING_SESSIONS);
        assertThat(pending.opensOn()).isNull();
    }

    @Test
    void aStudentWhoUsedTheirClassesMayPayBeforeTheDeadlineAndThePreviousCycleIsLeftAsItWas() {
        CycleRules rules = rules();
        CycleState done = completed("2026-10-30", "2026-10-08");
        var window = rules.paymentWindow(Optional.of(done), 0);
        assertThat(window.allowed()).isTrue();
        assertThat(window.paidOnMin()).isEqualTo(d("2026-10-08"));   // not before the day the last class was used
        assertThat(window.paidOnMax()).isEqualTo(d(TODAY));

        var opened = rules.openCycle(null, 8, Optional.of(done), 0);
        assertThat(opened.previousUpdated()).as("a COMPLETED cycle is not touched").isEmpty();
        assertThat(opened.newCycle().startDate()).isEqualTo(d(TODAY));
        assertThat(opened.newCycle().endDate()).isEqualTo(d("2026-11-09"));
    }

    @Test
    void oneClassLeftIsNotExhaustedAndAnUnmarkedClassNeverCounts() {
        CycleRules rules = rules();
        // 7 of 8 used, deadline in the future: still blocked; the 8th being "taken but not marked" does not change that
        assertThat(rules.paymentWindow(Optional.of(active("2026-10-30", 8, 7)), 0).allowed()).isFalse();
        assertThat(rules.paymentWindow(Optional.of(active("2026-10-30", 8, 7)), 1).allowed()).isFalse();
        // the moment the 8th is MARKED the cycle completes and the window opens
        assertThat(rules.paymentWindow(Optional.of(active("2026-10-30", 8, 8)), 0).allowed()).isTrue();
    }

    // ---- extension -----------------------------------------------------------------------------------------------

    private record ExtScenario(CycleState cycle, int pending, boolean hasNewer) {
    }

    private static Map<String, ExtScenario> extensionScenarios() {
        Map<String, ExtScenario> s = new LinkedHashMap<>();
        s.put("active", new ExtScenario(active("2026-10-12", 8, 3), 0, false));
        s.put("active, already extended near the cap", new ExtScenario(
                new CycleState(d("2026-09-09"), d("2026-12-05"), d("2026-10-09"), 8, 3, CycleStatus.ACTIVE, null), 0, false));
        s.put("active, at the cap", new ExtScenario(
                new CycleState(d("2026-09-09"), d("2026-12-08"), d("2026-10-09"), 8, 3, CycleStatus.ACTIVE, null), 0, false));
        s.put("active, deadline passed, unmarked class", new ExtScenario(active("2026-10-07", 8, 5), 1, false));
        s.put("completed", new ExtScenario(completed("2026-10-30", "2026-10-08"), 0, false));
        s.put("expired, the latest cycle", new ExtScenario(expired("2026-10-05"), 0, false));
        s.put("expired, a newer cycle exists", new ExtScenario(expired("2026-10-05"), 0, true));
        return s;
    }

    @Test
    void theExtensionWindowIsExactlyWhatExtendAccepts() {
        CycleRules rules = rules();
        int max = 60;
        extensionScenarios().forEach((name, s) -> {
            var window = rules.extensionWindow(s.cycle(), s.pending(), s.hasNewer(), max);
            for (LocalDate newEnd = d("2026-10-01"); !newEnd.isAfter(d("2026-12-20")); newEnd = newEnd.plusDays(1)) {
                boolean accepted;
                try {
                    rules.extend(s.cycle(), newEnd, java.util.UUID.randomUUID(), max, s.pending(), s.hasNewer());
                    accepted = true;
                } catch (CycleRuleException e) {
                    accepted = false;
                }
                boolean expected = window.allowed() && !newEnd.isBefore(window.from()) && !newEnd.isAfter(window.until());
                assertThat(accepted).as(name + " extending to " + newEnd).isEqualTo(expected);
            }
        });
    }

    @Test
    void theExtensionBoundsAreTheNextDayAndTheOriginalDeadlinePlusTheCap() {
        var window = rules().extensionWindow(active("2026-10-12", 8, 3), 0, false, 60);
        assertThat(window.allowed()).isTrue();
        assertThat(window.from()).isEqualTo(d("2026-10-13"));
        assertThat(window.until()).isEqualTo(d("2026-12-11"));
        // a reopened cycle cannot be given a deadline before today
        var reopen = rules().extensionWindow(expired("2026-10-05"), 0, false, 60);
        assertThat(reopen.from()).isEqualTo(d(TODAY));
        // at the cap there is nothing left to pick
        assertThat(rules().extensionWindow(extensionScenarios().get("active, at the cap").cycle(), 0, false, 60).allowed()).isFalse();
    }
}
