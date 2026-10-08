package com.coachplatform.scheduling;

import com.coachplatform.scheduling.api.AgendaView;
import com.coachplatform.scheduling.api.BlockCreated;
import com.coachplatform.scheduling.api.BlockInput;
import com.coachplatform.scheduling.api.BlockSummary;
import com.coachplatform.scheduling.api.BookSessionCommand;
import com.coachplatform.scheduling.api.CancelResult;
import com.coachplatform.scheduling.api.CoachCancelCommand;
import com.coachplatform.scheduling.api.MarkAttendanceCommand;
import com.coachplatform.scheduling.api.SessionSummary;
import com.coachplatform.scheduling.api.WindowInput;
import com.coachplatform.scheduling.api.WindowView;
import com.coachplatform.security.AuthPrincipal;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The coach's side of scheduling: availability, agenda, booking for a student, cancelling, attendance. */
@RestController
@RequestMapping("/api/coach")
class SchedulingController {

    private final SchedulingService scheduling;
    private final AvailabilityService availability;

    SchedulingController(SchedulingService scheduling, AvailabilityService availability) {
        this.scheduling = scheduling;
        this.availability = availability;
    }

    // ---- availability ----
    @GetMapping("/availability")
    List<WindowView> weekly() {
        return availability.weekly();
    }

    @PutMapping("/availability")
    List<WindowView> replaceWeekly(@Valid @RequestBody List<@Valid WindowInput> windows) {
        return availability.replaceWeekly(windows);
    }

    @GetMapping("/availability/blocks")
    List<BlockSummary> blocks(@RequestParam Instant from, @RequestParam Instant to) {
        return availability.blocks(from, to);
    }

    @PostMapping("/availability/blocks")
    @ResponseStatus(HttpStatus.CREATED)
    BlockCreated createBlock(@AuthenticationPrincipal AuthPrincipal me, @Valid @RequestBody BlockInput input) {
        return availability.createBlock(input, me.userId());
    }

    @DeleteMapping("/availability/blocks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteBlock(@PathVariable UUID id) {
        availability.deleteBlock(id);
    }

    // ---- agenda ----
    @GetMapping("/agenda")
    AgendaView agenda(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return scheduling.agenda(from, to);
    }

    @GetMapping("/sessions/pending")
    List<SessionSummary> pending() {
        return scheduling.pending();
    }

    @GetMapping("/students/{studentId}/sessions")
    List<SessionSummary> sessionsOfStudent(@PathVariable UUID studentId) {
        return scheduling.sessionsOfStudent(studentId);
    }

    @PostMapping("/students/{studentId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    SessionSummary book(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID studentId,
                        @Valid @RequestBody BookSessionCommand cmd) {
        return scheduling.bookAsCoach(studentId, cmd.startsAt(), me.userId());
    }

    @PostMapping("/sessions/{id}/attendance")
    SessionSummary attendance(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id,
                              @Valid @RequestBody MarkAttendanceCommand cmd) {
        return scheduling.markAttendance(id, cmd.result(), me.userId());
    }

    @PostMapping("/sessions/{id}/cancel")
    CancelResult cancel(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id,
                        @Valid @RequestBody CoachCancelCommand cmd) {
        return scheduling.cancelAsCoach(id, cmd.reason(), cmd.newStartsAt(), me.userId());
    }
}
