package com.coachplatform.scheduling;

import com.coachplatform.scheduling.api.AttendanceView;
import com.coachplatform.scheduling.api.BookCommand;
import com.coachplatform.scheduling.api.CancelResult;
import com.coachplatform.scheduling.api.StudentCancelCommand;
import com.coachplatform.scheduling.api.StudentSlotView;
import com.coachplatform.security.AuthPrincipal;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The student's side. Which student is ALWAYS taken from the token's user: no endpoint here accepts a student id, and
 * nothing here returns the name or id of another student, so there is nothing to tamper with and nothing to leak.
 */
@RestController
@RequestMapping("/api/student")
class StudentSchedulingController {

    private final SchedulingService scheduling;

    StudentSchedulingController(SchedulingService scheduling) {
        this.scheduling = scheduling;
    }

    @GetMapping("/slots")
    List<StudentSlotView> slots(@AuthenticationPrincipal AuthPrincipal me, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return scheduling.slotsForStudent(me.userId(), from, to);
    }

    @GetMapping("/sessions")
    List<AttendanceView> mySessions(@AuthenticationPrincipal AuthPrincipal me) {
        return scheduling.myAttendances(me.userId());
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    AttendanceView book(@AuthenticationPrincipal AuthPrincipal me, @Valid @RequestBody BookCommand cmd) {
        return scheduling.bookAsStudent(me.userId(), cmd.startsAt());
    }

    @PostMapping("/sessions/{id}/cancel")
    CancelResult cancel(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id,
                        @RequestBody(required = false) StudentCancelCommand cmd) {
        return scheduling.cancelAsStudent(me.userId(), id, cmd == null ? null : cmd.newStartsAt());
    }
}
