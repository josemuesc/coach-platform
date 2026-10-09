package com.coachplatform.scheduling.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.coachplatform.billing.api.CycleSummary;
import com.coachplatform.students.api.GuardianView;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The coach's sheet of one student, with the structure of the spreadsheet it replaces: header, classes of the cycle. */
public record SheetView(Header header, @Schema(nullable = true) CycleSummary cycle, List<ClassRow> classes, List<ClassRow> otherEntries) {

    public record Header(UUID studentId, String fullName, @Schema(nullable = true) String whatsappPhone, @Schema(nullable = true) GuardianView guardian, @Schema(nullable = true) String goal,
                         @Schema(nullable = true) LocalDate cycleStart, @Schema(nullable = true) LocalDate cycleEnd) {
    }
}
