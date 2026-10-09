package com.coachplatform.scheduling;

import com.coachplatform.scheduling.api.AuditEntryView;
import com.coachplatform.scheduling.api.ConfirmQrResult;
import com.coachplatform.scheduling.api.ConfirmResult;
import com.coachplatform.scheduling.api.HistoryView;
import com.coachplatform.scheduling.api.QrView;
import com.coachplatform.scheduling.api.SheetView;
import com.coachplatform.scheduling.api.TodayView;
import com.coachplatform.security.AuthPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Confirmation of classes, the rotating code, the coach's day / sheet / audit and the student's history. The student side never
 * takes a student id: the student is the token's user, so nobody can reach another student's classes.
 */
@RestController
class AttendanceController {

    private final AttendanceConfirmationService confirmations;
    private final AttendanceReportService reports;

    AttendanceController(AttendanceConfirmationService confirmations, AttendanceReportService reports) {
        this.confirmations = confirmations;
        this.reports = reports;
    }

    /** The token travels in the body, never in the URL, and is not printed by toString. */
    record ConfirmQrRequest(@NotBlank @Size(max = 200) String token) {
        @Override
        public String toString() {
            return "ConfirmQrRequest[token=<redacted>]";
        }
    }

    // ---- coach ------------------------------------------------------------------------------------------------

    @GetMapping("/api/coach/today")
    TodayView today() {
        return reports.today();
    }

    @GetMapping("/api/coach/students/{id}/sheet")
    SheetView sheet(@PathVariable UUID id, @RequestParam(required = false) UUID cycleId) {
        return reports.sheet(id, cycleId);
    }

    @GetMapping("/api/coach/events/{id}/qr")
    QrView qr(@PathVariable UUID id) {
        return confirmations.issueQr(id);
    }

    @GetMapping("/api/coach/attendances/{id}/audit")
    List<AuditEntryView> audit(@PathVariable UUID id) {
        return confirmations.auditOf(id);
    }

    // ---- student ----------------------------------------------------------------------------------------------

    @PostMapping("/api/student/attendances/confirm-qr")
    ConfirmQrResult confirmQr(@AuthenticationPrincipal AuthPrincipal me, @Valid @RequestBody ConfirmQrRequest req) {
        return confirmations.confirmByQr(me.userId(), req.token());
    }

    @PostMapping("/api/student/attendances/{id}/confirm")
    ConfirmResult confirm(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id) {
        return confirmations.confirmLater(me.userId(), id);
    }

    @GetMapping("/api/student/history")
    HistoryView history(@AuthenticationPrincipal AuthPrincipal me) {
        return reports.history(me.userId());
    }
}
