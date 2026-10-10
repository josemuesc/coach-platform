package com.coachplatform.scheduling;

import com.coachplatform.scheduling.api.AgendaView;
import com.coachplatform.scheduling.api.AgendaWeekView;
import com.coachplatform.scheduling.api.AttendanceView;
import com.coachplatform.scheduling.api.BlockCreated;
import com.coachplatform.scheduling.api.BlockInput;
import com.coachplatform.scheduling.api.BlockPreview;
import com.coachplatform.scheduling.api.BookableStudent;
import com.coachplatform.scheduling.api.BookingOptions;
import com.coachplatform.scheduling.api.DayWindowInput;
import com.coachplatform.scheduling.api.BlockSummary;
import com.coachplatform.scheduling.api.CancelAttendancesCommand;
import com.coachplatform.scheduling.api.CancelAttendancesResult;
import com.coachplatform.scheduling.api.CancelEventCommand;
import com.coachplatform.scheduling.api.CancelResult;
import com.coachplatform.scheduling.api.ChangeCapacityCommand;
import com.coachplatform.scheduling.api.CoachBookCommand;
import com.coachplatform.scheduling.api.CoachCancelCommand;
import com.coachplatform.scheduling.api.EventCancelResult;
import com.coachplatform.scheduling.api.EventView;
import com.coachplatform.scheduling.api.MarkCommand;
import com.coachplatform.scheduling.api.MarkEventCommand;
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

/** The coach's side of scheduling: availability, agenda, booking a student, attendances and events. */
@RestController
@RequestMapping("/api/coach")
class SchedulingController {

    private final SchedulingService scheduling;
    private final AvailabilityService availability;
    private final BlockService blockService;
    private final CoachAgendaService agenda;

    SchedulingController(SchedulingService scheduling, AvailabilityService availability, BlockService blockService,
                         CoachAgendaService agenda) {
        this.scheduling = scheduling;
        this.availability = availability;
        this.blockService = blockService;
        this.agenda = agenda;
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

    /** Replaces the windows of one weekday (1 = Monday ... 7 = Sunday); the other days are left alone. */
    @PutMapping("/availability/days/{dayOfWeek}")
    List<WindowView> replaceDay(@PathVariable int dayOfWeek, @Valid @RequestBody List<@Valid DayWindowInput> windows) {
        return availability.replaceDay(dayOfWeek, windows);
    }

    @GetMapping("/availability/blocks")
    List<BlockSummary> blocks(@RequestParam Instant from, @RequestParam Instant to) {
        return availability.blocks(from, to);
    }

    @GetMapping("/availability/blocks/upcoming")
    List<BlockSummary> upcomingBlocks() {
        return availability.upcomingBlocks();
    }

    /** What saving the block would do (the classes it would release); writes nothing. */
    @PostMapping("/availability/blocks/preview")
    BlockPreview previewBlock(@Valid @RequestBody BlockInput input) {
        return blockService.preview(input);
    }

    @PostMapping("/availability/blocks")
    @ResponseStatus(HttpStatus.CREATED)
    BlockCreated createBlock(@AuthenticationPrincipal AuthPrincipal me, @Valid @RequestBody BlockInput input) {
        return blockService.create(input, me.userId());
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

    @GetMapping("/agenda/week")
    AgendaWeekView agendaWeek(@RequestParam LocalDate date) {
        return agenda.week(date);
    }

    @GetMapping("/booking/students")
    List<BookableStudent> bookableStudents() {
        return agenda.bookableStudents();
    }

    @GetMapping("/students/{studentId}/booking-options")
    BookingOptions bookingOptions(@PathVariable UUID studentId, @RequestParam LocalDate date) {
        return agenda.bookingOptions(studentId, date);
    }

    @GetMapping("/attendances/pending")
    List<AttendanceView> pending() {
        return scheduling.pending();
    }

    @GetMapping("/students/{studentId}/sessions")
    List<AttendanceView> attendancesOfStudent(@PathVariable UUID studentId) {
        return scheduling.attendancesOfStudent(studentId);
    }

    @PostMapping("/students/{studentId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    AttendanceView book(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID studentId,
                        @Valid @RequestBody CoachBookCommand cmd) {
        return scheduling.bookAsCoach(studentId, cmd.startsAt(), me.userId(), cmd.override(), cmd.overrideReason());
    }

    // ---- one student's place ----
    @PostMapping("/attendances/{id}/mark")
    AttendanceView mark(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id, @Valid @RequestBody MarkCommand cmd) {
        return scheduling.markAttendance(id, cmd.result(), me.userId());
    }

    /** All-or-nothing cancellation of several of one student's classes (e.g. before renewing into another modality). */
    @PostMapping("/students/{studentId}/attendances/cancel")
    CancelAttendancesResult cancelAttendances(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID studentId,
                                              @Valid @RequestBody CancelAttendancesCommand cmd) {
        return scheduling.cancelManyAsCoach(studentId, cmd.attendanceIds(), cmd.reason(), me.userId());
    }

    @PostMapping("/attendances/{id}/cancel")
    CancelResult cancelAttendance(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id,
                                  @Valid @RequestBody CoachCancelCommand cmd) {
        return scheduling.cancelAsCoach(id, cmd.reason(), cmd.newStartsAt(), cmd.override(), cmd.overrideReason(), me.userId());
    }

    // ---- the event as a whole ----
    @PostMapping("/events/{id}/mark")
    List<AttendanceView> markEvent(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id,
                                   @Valid @RequestBody MarkEventCommand cmd) {
        return scheduling.markEvent(id, cmd.marks(), me.userId());
    }

    @PostMapping("/events/{id}/cancel")
    EventCancelResult cancelEvent(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id,
                                  @Valid @RequestBody CancelEventCommand cmd) {
        return scheduling.cancelEvent(id, cmd.reason(), me.userId());
    }

    @PutMapping("/events/{id}/capacity")
    EventView changeCapacity(@PathVariable UUID id, @Valid @RequestBody ChangeCapacityCommand cmd) {
        return scheduling.changeCapacity(id, cmd.capacity());
    }
}
