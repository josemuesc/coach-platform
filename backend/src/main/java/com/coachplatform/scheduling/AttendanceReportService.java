package com.coachplatform.scheduling;

import com.coachplatform.billing.BillingService;
import com.coachplatform.billing.api.CycleSummary;
import com.coachplatform.coach.CoachService;
import com.coachplatform.coach.api.SchedulingSettings;
import com.coachplatform.common.ApiException;
import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.api.ClassRow;
import com.coachplatform.scheduling.api.EventStatus;
import com.coachplatform.scheduling.api.HistoryView;
import com.coachplatform.scheduling.api.SheetView;
import com.coachplatform.scheduling.api.TodayView;
import com.coachplatform.scheduling.domain.ConfirmationRules;
import com.coachplatform.scheduling.domain.SlotCalendar;
import com.coachplatform.students.StudentService;
import com.coachplatform.students.api.StudentSummary;
import com.coachplatform.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read models for the frontend: the coach's day, the coach's sheet of a student and the student's own history. Nothing here
 * writes. The student's history is built ONLY from the student of the token and never carries another student's name or id.
 */
@Service
public class AttendanceReportService {

    private static final int HISTORY_CYCLES = 12;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final ClassSessionRepository events;
    private final SessionAttendanceRepository attendances;
    private final SchedulingViews views;
    private final StudentService students;
    private final BillingService billing;
    private final CoachService coaches;
    private final ConfirmationRules confirmation;
    private final SlotCalendar calendar;
    private final Clock clock;

    AttendanceReportService(ClassSessionRepository events, SessionAttendanceRepository attendances, SchedulingViews views,
                            StudentService students, BillingService billing, CoachService coaches, ConfirmationRules confirmation,
                            SlotCalendar calendar, Clock clock) {
        this.events = events;
        this.attendances = attendances;
        this.views = views;
        this.students = students;
        this.billing = billing;
        this.coaches = coaches;
        this.confirmation = confirmation;
        this.calendar = calendar;
        this.clock = clock;
    }

    /** The coach's events of TODAY (the America/Bogota calendar day, never the UTC one) with attendees, status, confirmation and free seats. */
    @Transactional(readOnly = true)
    public TodayView today() {
        LocalDate today = LocalDate.now(clock.withZone(calendar.zone()));
        Instant from = today.atStartOfDay(calendar.zone()).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(calendar.zone()).toInstant();
        List<ClassSession> scheduled = events.findByStatusAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAt(
                EventStatus.SCHEDULED, from, to);
        return new TodayView(today.toString(), views.eventViews(scheduled));
    }

    /**
     * The sheet of one student, with the structure of the spreadsheet it replaces: header (name, phone, goal, cycle start and end)
     * and the classes of a cycle. The cycle is the one asked for, else the active one, else the latest.
     */
    @Transactional(readOnly = true)
    public SheetView sheet(UUID studentId, UUID cycleId) {
        StudentSummary student = students.get(studentId);                               // 404 for a student of another tenant
        CycleSummary cycle = pickCycle(studentId, cycleId);
        SchedulingSettings settings = coaches.schedulingSettings(TenantContext.get());
        List<ClassRow> live = List.of();
        List<ClassRow> other = List.of();
        if (cycle != null) {
            List<SessionAttendance> places = attendances.findByStudentIdOrderByCreatedAtDesc(studentId).stream()
                    .filter(a -> a.getCycleId().equals(cycle.id())).toList();
            Rows rows = rows(places, settings.confirmationWindowHours());
            live = rows.live();
            other = rows.other();
        }
        var header = new SheetView.Header(student.id(), student.fullName(), student.whatsappPhone(), student.guardian(), student.goal(),
                cycle == null ? null : cycle.startDate(), cycle == null ? null : cycle.endDate());
        return new SheetView(header, cycle, live, other);
    }

    /** The logged-in student's own classes per cycle (latest 12), their active cycle and nothing about anybody else. */
    @Transactional(readOnly = true)
    public HistoryView history(UUID studentUserId) {
        StudentSummary student = students.findByUserId(studentUserId);
        SchedulingSettings settings = coaches.schedulingSettings(TenantContext.get());
        List<CycleSummary> cycles = billing.cycles(student.id()).stream().limit(HISTORY_CYCLES).toList();
        Map<UUID, List<SessionAttendance>> byCycle = attendances.findByStudentIdOrderByCreatedAtDesc(student.id()).stream()
                .collect(Collectors.groupingBy(SessionAttendance::getCycleId));
        List<HistoryView.Cycle> result = new ArrayList<>();
        for (CycleSummary cycle : cycles) {
            Rows rows = rows(byCycle.getOrDefault(cycle.id(), List.of()), settings.confirmationWindowHours());
            result.add(new HistoryView.Cycle(cycle, rows.live(), rows.other()));
        }
        CycleSummary active = billing.activeCycle(student.id()).orElse(null);
        return new HistoryView(active, result);
    }

    private CycleSummary pickCycle(UUID studentId, UUID cycleId) {
        List<CycleSummary> all = billing.cycles(studentId);
        if (cycleId != null) {
            return all.stream().filter(c -> c.id().equals(cycleId)).findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CYCLE_NOT_FOUND"));
        }
        return billing.activeCycle(studentId).orElse(all.isEmpty() ? null : all.get(0));
    }

    private record Rows(List<ClassRow> live, List<ClassRow> other) {
    }

    /** Live places (booked / attended / no-show) numbered in date order; cancelled and rescheduled ones listed apart, unnumbered. */
    private Rows rows(List<SessionAttendance> places, int windowHours) {
        if (places.isEmpty()) {
            return new Rows(List.of(), List.of());
        }
        Map<UUID, ClassSession> eventById = events.findAllById(places.stream().map(SessionAttendance::getSessionId).distinct().toList()).stream()
                .collect(Collectors.toMap(ClassSession::getId, Function.identity()));
        List<SessionAttendance> sorted = places.stream()
                .sorted(Comparator.comparing((SessionAttendance a) -> eventById.get(a.getSessionId()).getStartsAt()).thenComparing(SessionAttendance::getId)).toList();
        List<ClassRow> live = new ArrayList<>();
        List<ClassRow> other = new ArrayList<>();
        for (SessionAttendance a : sorted) {
            ClassSession e = eventById.get(a.getSessionId());
            AttendanceStatus status = a.getStatus();
            boolean isLive = status.isLive();
            var local = e.getStartsAt().atZone(calendar.zone());
            (isLive ? live : other).add(new ClassRow(isLive ? live.size() + 1 : null, a.getId(), e.getStartsAt(),
                    local.toLocalDate().toString(), local.toLocalTime().format(HH_MM), status, a.isConfirmed(), a.getStudentConfirmationMethod(),
                    ConfirmationRules.isOnlyMarkedByCoach(status, a.isConfirmed()), a.isOverride(),
                    confirmation.canConfirmLater(status, a.isConfirmed(), e.getStartsAt(), windowHours), a.getCancelReason()));
        }
        return new Rows(live, other);
    }
}
