package com.coachplatform.scheduling.api;

import com.coachplatform.billing.api.CycleSummary;
import com.coachplatform.students.api.GuardianView;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The coach's sheet of one student, with the structure of the spreadsheet it replaces: header, classes of the cycle. */
public record SheetView(Header header, CycleSummary cycle, List<ClassRow> classes, List<ClassRow> otherEntries) {

    public record Header(UUID studentId, String fullName, String whatsappPhone, GuardianView guardian, String goal,
                         LocalDate cycleStart, LocalDate cycleEnd) {
    }
}
