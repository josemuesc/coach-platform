package com.coachplatform.scheduling.api;

import com.coachplatform.billing.api.CycleSummary;
import java.util.List;

/** The student's own history: the active cycle and their classes per cycle (latest first). Nobody else is ever identified. */
public record HistoryView(CycleSummary activeCycle, List<Cycle> cycles) {

    public record Cycle(CycleSummary cycle, List<ClassRow> classes, List<ClassRow> otherEntries) {
    }
}
