package com.coachplatform.scheduling;

import com.coachplatform.billing.BillingService;
import com.coachplatform.billing.api.CycleStatus;
import com.coachplatform.billing.api.CycleSummary;
import com.coachplatform.coach.CoachService;
import com.coachplatform.coach.api.SchedulingSettings;
import com.coachplatform.scheduling.api.AgendaDay;
import com.coachplatform.scheduling.api.AgendaRow;
import com.coachplatform.scheduling.api.AgendaRowKind;
import com.coachplatform.scheduling.api.AgendaWeekView;
import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.api.BookBlockedReason;
import com.coachplatform.scheduling.api.BookableStudent;
import com.coachplatform.scheduling.api.BookingOptions;
import com.coachplatform.scheduling.api.BookingSlot;
import com.coachplatform.scheduling.api.EventView;
import com.coachplatform.scheduling.api.OverrideRule;
import com.coachplatform.scheduling.api.WindowView;
import com.coachplatform.scheduling.domain.BlockRules;
import com.coachplatform.scheduling.domain.BookingRules;
import com.coachplatform.scheduling.domain.BookingRules.CycleSnapshot;
import com.coachplatform.scheduling.domain.SlotCalendar;
import com.coachplatform.scheduling.domain.SlotCalendar.Range;
import com.coachplatform.scheduling.domain.SlotCalendar.Window;
import com.coachplatform.scheduling.domain.SlotCatalog;
import com.coachplatform.scheduling.domain.SlotCatalog.CoachSlot;
import com.coachplatform.students.StudentService;
import com.coachplatform.students.api.StudentSummary;
import com.coachplatform.tenant.TenantContext;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the coach's calendar screens: the week as one list of rows per day (classes, free slots and blocks, with the counts),
 * who can be booked, and what can be booked for one student on one day. Everything is decided here: the client never subtracts
 * schedule, classes and blocks, and never decides who is bookable.
 */
@Service
public class CoachAgendaService {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final ClassSessionRepository events;
    private final SessionAttendanceRepository attendances;
    private final AvailabilityRuleRepository availability;
    private final AvailabilityBlockRepository blocks;
    private final SchedulingViews views;
    private final StudentService students;
    private final BillingService billing;
    private final CoachService coaches;
    private final SlotCalendar calendar;
    private final BlockRules blockRules;
    private final Clock clock;

    CoachAgendaService(ClassSessionRepository events, SessionAttendanceRepository attendances, AvailabilityRuleRepository availability,
                       AvailabilityBlockRepository blocks, SchedulingViews views, StudentService students, BillingService billing,
                       CoachService coaches, SlotCalendar calendar, BlockRules blockRules, Clock clock) {
        this.events = events;
        this.attendances = attendances;
        this.availability = availability;
        this.blocks = blocks;
        this.views = views;
        this.students = students;
        this.billing = billing;
        this.coaches = coaches;
        this.calendar = calendar;
        this.blockRules = blockRules;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------------------------------- the week

    /** Monday..Sunday of the week holding {@code date} (a local date of the coach). */
    @Transactional(readOnly = true)
    public AgendaWeekView week(LocalDate date) {
        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Instant start = startOf(monday);
        Instant end = startOf(monday.plusDays(7));
        SchedulingSettings settings = coaches.schedulingSettings(TenantContext.get());
        List<Window> windows = windows();
        List<AvailabilityBlock> weekBlocks = blocks.findOverlapping(start, end);
        List<ClassSession> scheduled = events.findByStatusAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAt(
                com.coachplatform.scheduling.api.EventStatus.SCHEDULED, start, end);
        List<Range> taken = events.findScheduledOverlapping(start, end).stream().map(e -> new Range(e.getStartsAt(), e.getEndsAt())).toList();
        List<Range> free = calendar.freeSlots(monday, monday.plusDays(6), windows, Duration.ofMinutes(settings.classDurationMinutes()),
                weekBlocks.stream().map(b -> new Range(b.getStartsAt(), b.getEndsAt())).toList(), taken, clock.instant());
        List<EventView> eventViews = views.eventViews(scheduled);

        List<AgendaDay> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate day = monday.plusDays(i);
            Instant dayStart = startOf(day);
            Instant dayEnd = startOf(day.plusDays(1));
            List<AgendaRow> items = new ArrayList<>();
            int classCount = 0;
            int freeCount = 0;
            for (EventView e : eventViews) {
                if (!e.startsAt().isBefore(dayStart) && e.startsAt().isBefore(dayEnd)) {
                    items.add(new AgendaRow(AgendaRowKind.EVENT, e.startsAt(), e.endsAt(), localTime(e.startsAt()), e, null, null, false));
                    classCount++;
                }
            }
            for (Range r : free) {
                if (!r.start().isBefore(dayStart) && r.start().isBefore(dayEnd)) {
                    items.add(new AgendaRow(AgendaRowKind.FREE, r.start(), r.end(), localTime(r.start()), null, null, null, false));
                    freeCount++;
                }
            }
            for (AvailabilityBlock b : weekBlocks) {
                Instant from = b.getStartsAt().isBefore(dayStart) ? dayStart : b.getStartsAt();
                Instant to = b.getEndsAt().isAfter(dayEnd) ? dayEnd : b.getEndsAt();
                if (from.isBefore(to)) {
                    items.add(new AgendaRow(AgendaRowKind.BLOCK, from, to, localTime(from), null, b.getId(), b.getReason(),
                            blockRules.isAllDay(new Range(from, to))));
                }
            }
            items.sort(Comparator.comparing(AgendaRow::startsAt).thenComparing(AgendaRow::kind));
            boolean hasAvailability = windows.stream().anyMatch(w -> w.day() == day.getDayOfWeek());
            days.add(new AgendaDay(day, hasAvailability, classCount, freeCount, items));
        }
        return new AgendaWeekView(monday, days);
    }

    // ---------------------------------------------------------------------------------------------- who can be booked

    /** Active students for the "book a class" selector, by name, each with whether they can be booked now and why not. */
    @Transactional(readOnly = true)
    public List<BookableStudent> bookableStudents() {
        return students.list().stream().filter(StudentSummary::active)
                .sorted(Comparator.comparing(StudentSummary::fullName, String.CASE_INSENSITIVE_ORDER))
                .map(s -> {
                    CycleSummary cycle = billing.activeCycle(s.id()).orElse(null);
                    if (cycle == null) {
                        return new BookableStudent(s.id(), s.fullName(), s.minor(), null, 0, false, BookBlockedReason.NO_ACTIVE_CYCLE);
                    }
                    int available = BookingRules.classesAvailable(snapshot(cycle));
                    return new BookableStudent(s.id(), s.fullName(), s.minor(), cycle.modality(), available, available > 0,
                            available > 0 ? null : BookBlockedReason.NO_CLASSES_LEFT);
                }).toList();
    }

    // ---------------------------------------------------------------------------------------------- what can be booked

    @Transactional(readOnly = true)
    public BookingOptions bookingOptions(UUID studentId, LocalDate date) {
        students.get(studentId);   // 404 for a student of another tenant
        List<WindowView> dayWindows = windows().stream().filter(w -> w.day() == date.getDayOfWeek())
                .map(w -> new WindowView(w.day().getValue(), w.start().toString(), w.end().toString())).toList();
        CycleSummary cycle = billing.activeCycle(studentId).orElse(null);
        if (cycle == null) {
            return new BookingOptions(studentId, date, null, 0, false, BookBlockedReason.NO_ACTIVE_CYCLE, null, dayWindows, List.of());
        }
        int available = BookingRules.classesAvailable(snapshot(cycle));
        if (available < 1) {
            return new BookingOptions(studentId, date, cycle.modality(), 0, false, BookBlockedReason.NO_CLASSES_LEFT, cycle.endDate(),
                    dayWindows, List.of());
        }
        List<BookingSlot> slots = date.isAfter(cycle.endDate()) ? List.of()
                : coachSlots(studentId, cycle, date, date, clock.instant()).stream().map(this::toSlot).toList();
        return new BookingOptions(studentId, date, cycle.modality(), available, true, null, cycle.endDate(), dayWindows, slots);
    }

    /** How many places a student of this cycle could still book from today until the cycle's deadline, with the notice a student needs. */
    int bookablePlacesUntilDeadline(UUID studentId, CycleSummary cycle) {
        LocalDate today = clock.instant().atZone(calendar.zone()).toLocalDate();
        if (cycle.endDate().isBefore(today)) {
            return 0;
        }
        SchedulingSettings settings = coaches.schedulingSettings(TenantContext.get());
        Instant notBefore = clock.instant().plus(Duration.ofHours(settings.cancelWindowHours()));
        return (int) coachSlots(studentId, cycle, today, cycle.endDate(), notBefore).stream().filter(s -> !s.needsOverride()).count();
    }

    CycleSnapshot snapshot(CycleSummary cycle) {
        return new CycleSnapshot(cycle.status() == CycleStatus.ACTIVE, cycle.endDate(), cycle.classesIncluded(), cycle.classesUsed(),
                attendances.countByCycleIdAndStatus(cycle.id(), AttendanceStatus.SCHEDULED), cycle.modality());
    }

    private List<CoachSlot> coachSlots(UUID studentId, CycleSummary cycle, LocalDate from, LocalDate to, Instant notBefore) {
        SchedulingSettings settings = coaches.schedulingSettings(TenantContext.get());
        Instant start = startOf(from);
        Instant end = startOf(to.plusDays(1));
        List<Range> grid = calendar.freeSlots(from, to, windows(), Duration.ofMinutes(settings.classDurationMinutes()),
                blockRanges(start, end), List.of(), notBefore);
        List<ClassSession> inRange = events.findScheduledOverlapping(start, end);
        Map<UUID, Integer> counts = views.liveCounts(inRange.stream().map(ClassSession::getId).toList());
        List<SlotCatalog.EventInfo> infos = inRange.stream().map(e -> new SlotCatalog.EventInfo(e.getId(),
                new Range(e.getStartsAt(), e.getEndsAt()), e.getModality(), e.getCapacity(), counts.getOrDefault(e.getId(), 0))).toList();
        Set<UUID> mine = infos.stream().map(SlotCatalog.EventInfo::id).filter(id -> attendances.existsLive(id, studentId))
                .collect(Collectors.toCollection(HashSet::new));
        return SlotCatalog.forCoach(cycle.modality(), settings.defaultGroupCapacity(), grid, infos, mine);
    }

    private BookingSlot toSlot(CoachSlot s) {
        OverrideRule rule = s.blockedBy() == null ? null : OverrideRule.valueOf(s.blockedBy().name());
        return new BookingSlot(s.range().start(), s.range().end(), localTime(s.range().start()), s.modality(), s.capacity(), s.occupied(),
                s.eventId(), s.needsOverride(), rule);
    }

    private List<Window> windows() {
        return availability.findAllByOrderByDayOfWeekAscStartTimeAsc().stream()
                .map(r -> new Window(DayOfWeek.of(r.getDayOfWeek()), r.getStartTime(), r.getEndTime())).toList();
    }

    private List<Range> blockRanges(Instant from, Instant to) {
        return blocks.findOverlapping(from, to).stream().map(b -> new Range(b.getStartsAt(), b.getEndsAt())).toList();
    }

    private Instant startOf(LocalDate date) {
        return date.atStartOfDay(calendar.zone()).toInstant();
    }

    private String localTime(Instant instant) {
        return instant.atZone(calendar.zone()).format(HH_MM);
    }
}
