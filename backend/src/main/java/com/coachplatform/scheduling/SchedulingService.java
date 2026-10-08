package com.coachplatform.scheduling;

import com.coachplatform.billing.BillingService;
import com.coachplatform.billing.api.CycleStatus;
import com.coachplatform.billing.api.CycleSummary;
import com.coachplatform.coach.CoachService;
import com.coachplatform.coach.api.SchedulingSettings;
import com.coachplatform.common.ApiException;
import com.coachplatform.scheduling.api.AgendaView;
import com.coachplatform.scheduling.api.CancelResult;
import com.coachplatform.scheduling.api.SessionStatus;
import com.coachplatform.scheduling.api.SessionSummary;
import com.coachplatform.scheduling.api.SlotView;
import com.coachplatform.scheduling.domain.AttendanceRules;
import com.coachplatform.scheduling.domain.BookingRules;
import com.coachplatform.scheduling.domain.BookingRules.CycleSnapshot;
import com.coachplatform.scheduling.domain.CancellationPolicy;
import com.coachplatform.scheduling.domain.SchedulingRuleException;
import com.coachplatform.scheduling.domain.SlotCalendar;
import com.coachplatform.scheduling.domain.SlotCalendar.Range;
import com.coachplatform.scheduling.domain.SlotCalendar.Window;
import com.coachplatform.students.StudentService;
import com.coachplatform.students.api.StudentSummary;
import com.coachplatform.tenant.TenantContext;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API of scheduling. Every write on a student's classes takes the STUDENT ROW LOCK FIRST (the global lock
 * order: student, then cycle/session rows), reads the current state under it, asks the pure domain rules for the
 * decision, and persists. The database's exclusion constraint is the last guard against two classes overlapping.
 */
@Service
public class SchedulingService {

    private static final int MAX_RANGE_DAYS = 62;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final StudentService students;
    private final BillingService billing;
    private final CoachService coaches;
    private final ClassSessionRepository sessions;
    private final AvailabilityRuleRepository availability;
    private final AvailabilityBlockRepository blocks;
    private final BookingRules bookingRules;
    private final CancellationPolicy cancellation;
    private final AttendanceRules attendance;
    private final SlotCalendar calendar;
    private final Clock clock;

    SchedulingService(StudentService students, BillingService billing, CoachService coaches,
                      ClassSessionRepository sessions, AvailabilityRuleRepository availability,
                      AvailabilityBlockRepository blocks, BookingRules bookingRules, CancellationPolicy cancellation,
                      AttendanceRules attendance, SlotCalendar calendar, Clock clock) {
        this.students = students;
        this.billing = billing;
        this.coaches = coaches;
        this.sessions = sessions;
        this.availability = availability;
        this.blocks = blocks;
        this.bookingRules = bookingRules;
        this.cancellation = cancellation;
        this.attendance = attendance;
        this.calendar = calendar;
        this.clock = clock;
    }

    // =================================================================================================
    // reads
    // =================================================================================================

    /** The coach's agenda for local dates [from, to]: every class in the range plus the free slots. */
    @Transactional(readOnly = true)
    public AgendaView agenda(LocalDate from, LocalDate to) {
        Instant start = startOf(from);
        Instant end = startOf(to.plusDays(1));
        checkRange(from, to);
        Map<UUID, String> names = names();
        List<SessionSummary> list = sessions.findByStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAt(start, end)
                .stream().map(s -> summary(s, names)).toList();
        return new AgendaView(list, freeSlots(from, to, clock.instant(), null));
    }

    /** Free slots for a student: only those the student could really book (lead time, inside the active cycle). */
    @Transactional(readOnly = true)
    public List<SlotView> slotsForStudent(UUID studentUserId, LocalDate from, LocalDate to) {
        checkRange(from, to);
        StudentSummary student = students.findByUserId(studentUserId);
        CycleSummary cycle = billing.activeCycle(student.id()).orElse(null);
        if (cycle == null) {
            return List.of();
        }
        SchedulingSettings settings = coaches.schedulingSettings(TenantContext.get());
        Instant notBefore = clock.instant().plus(Duration.ofHours(settings.cancelWindowHours())).minusSeconds(1);
        LocalDate lastDay = to.isAfter(cycle.endDate()) ? cycle.endDate() : to;
        return lastDay.isBefore(from) ? List.of() : freeSlots(from, lastDay, notBefore, settings);
    }

    @Transactional(readOnly = true)
    public List<SessionSummary> pending() {
        Map<UUID, String> names = names();
        return sessions.findByStatusAndStartsAtLessThanEqualOrderByStartsAt(SessionStatus.SCHEDULED, clock.instant())
                .stream().map(s -> summary(s, names)).toList();
    }

    @Transactional(readOnly = true)
    public List<SessionSummary> sessionsOfStudent(UUID studentId) {
        students.get(studentId); // 404 for a student of another tenant
        return sessions.findByStudentIdOrderByStartsAtDesc(studentId).stream().map(s -> summary(s, Map.of())).toList();
    }

    /** The logged-in student's own classes. The student comes from the token, never from a request parameter. */
    @Transactional(readOnly = true)
    public List<SessionSummary> mySessions(UUID studentUserId) {
        StudentSummary student = students.findByUserId(studentUserId);
        return sessions.findByStudentIdOrderByStartsAtDesc(student.id()).stream().map(s -> summary(s, Map.of())).toList();
    }

    // =================================================================================================
    // booking
    // =================================================================================================

    @Transactional
    public SessionSummary bookAsStudent(UUID studentUserId, Instant startsAt) {
        StudentSummary student = students.findByUserId(studentUserId);
        return book(student.id(), startsAt, studentUserId, true);
    }

    @Transactional
    public SessionSummary bookAsCoach(UUID studentId, Instant startsAt, UUID coachUserId) {
        return book(studentId, startsAt, coachUserId, false);
    }

    private SessionSummary book(UUID studentId, Instant startsAt, UUID byUserId, boolean byStudent) {
        students.lockForUpdate(studentId);                                   // 1st lock: the student
        coaches.lockCalendar(TenantContext.get());                           // 2nd lock: the coach's calendar
        SchedulingSettings settings = coaches.schedulingSettings(TenantContext.get());
        CycleSummary cycle = billing.activeCycle(studentId).orElse(null);
        Duration duration = Duration.ofMinutes(settings.classDurationMinutes());

        CycleSnapshot snapshot = cycle == null ? null : snapshot(cycle, sessions.countByCycleIdAndStatus(cycle.id(), SessionStatus.SCHEDULED));
        Range range = bookingRules.validate(new BookingRules.Request(startsAt, duration, snapshot, windows(),
                blockRanges(startsAt, startsAt.plus(duration)), bookedRanges(startsAt, startsAt.plus(duration), null),
                byStudent, settings.cancelWindowHours(), true));

        ClassSession session = persistNew(new ClassSession(studentId, cycle.id(), range.start(), range.end(), null, byUserId));
        return summary(session, byStudent ? Map.of() : names());
    }

    // =================================================================================================
    // cancelling and rescheduling
    // =================================================================================================

    /**
     * A student cancels (and optionally moves) their own class. Free only with enough notice; with a new date the
     * change is atomic: if the new slot is not valid, nothing happens and the original class stays as it was.
     */
    @Transactional
    public CancelResult cancelAsStudent(UUID studentUserId, UUID sessionId, Instant newStartsAt) {
        StudentSummary student = students.findByUserId(studentUserId);
        students.lockForUpdate(student.id());                                // 1st lock: the student
        ClassSession session = sessions.findById(sessionId).orElseThrow(SessionNotFoundException::new);   // read under the lock
        if (!session.getStudentId().equals(student.id())) {
            throw new SessionNotFoundException();                            // someone else's class: reveal nothing
        }

        SchedulingSettings settings = coaches.schedulingSettings(TenantContext.get());
        cancellation.requireStudentMayCancel(session.getStatus(), session.getStartsAt(), settings.cancelWindowHours());
        return cancelAndMaybeReplace(session, newStartsAt, true, studentUserId, null, settings,
                SessionStatus.CANCELLED_ON_TIME);
    }

    /**
     * The coach cancels a class: always allowed (no window), reason mandatory, never deducts a class. This is also how
     * a late student cancellation is forgiven. With a new date, the replacement is created atomically.
     */
    @Transactional
    public CancelResult cancelAsCoach(UUID sessionId, String reason, Instant newStartsAt, UUID coachUserId) {
        UUID studentId = sessions.findStudentIdById(sessionId).orElseThrow(SessionNotFoundException::new);
        students.lockForUpdate(studentId);                                   // 1st lock: the student
        ClassSession session = sessions.findById(sessionId).orElseThrow(SessionNotFoundException::new);   // read under the lock

        cancellation.requireCoachMayCancel(session.getStatus(), reason);
        SchedulingSettings settings = coaches.schedulingSettings(TenantContext.get());
        return cancelAndMaybeReplace(session, newStartsAt, false, coachUserId, reason.trim(), settings,
                SessionStatus.CANCELLED_BY_COACH);
    }

    private CancelResult cancelAndMaybeReplace(ClassSession session, Instant newStartsAt, boolean byStudent, UUID byUser,
                                               String reason, SchedulingSettings settings, SessionStatus plainCancelStatus) {
        Instant now = clock.instant();
        Map<UUID, String> names = byStudent ? Map.of() : names();   // the student's own views never carry names
        if (newStartsAt == null) {
            session.cancel(plainCancelStatus, byUser, reason, now);
            return new CancelResult(summary(sessions.saveAndFlush(session), names), null);
        }

        // Validate the NEW slot before touching the original: any failure leaves the original class untouched.
        coaches.lockCalendar(TenantContext.get());                           // 2nd lock: the coach's calendar
        CycleSummary cycle = billing.cycle(session.getCycleId());
        Duration duration = Duration.ofMinutes(settings.classDurationMinutes());
        CycleSnapshot snapshot = snapshot(cycle, sessions.countByCycleIdAndStatus(cycle.id(), SessionStatus.SCHEDULED));
        Range range = bookingRules.validate(new BookingRules.Request(newStartsAt, duration, snapshot, windows(),
                blockRanges(newStartsAt, newStartsAt.plus(duration)),
                bookedRanges(newStartsAt, newStartsAt.plus(duration), session.getId()),   // the replaced class frees its place
                byStudent, settings.cancelWindowHours(), false));

        // The original is released and flushed FIRST: the new class may overlap it (e.g. moving 30 minutes later).
        session.cancel(byStudent ? SessionStatus.RESCHEDULED : plainCancelStatus, byUser, reason, now);
        sessions.saveAndFlush(session);
        ClassSession replacement = persistNew(new ClassSession(session.getStudentId(), session.getCycleId(),
                range.start(), range.end(), session.getId(), byUser));
        return new CancelResult(summary(session, names), summary(replacement, names));
    }

    // =================================================================================================
    // attendance
    // =================================================================================================

    /** The coach marks a class ATTENDED or NO_SHOW. Marking uses up one class of the cycle and cannot be undone. */
    @Transactional
    public SessionSummary markAttendance(UUID sessionId, SessionStatus result, UUID coachUserId) {
        UUID studentId = sessions.findStudentIdById(sessionId).orElseThrow(SessionNotFoundException::new);
        students.lockForUpdate(studentId);                                   // 1st lock: the student
        ClassSession session = sessions.findById(sessionId).orElseThrow(SessionNotFoundException::new);   // read under the lock

        CycleSummary cycle = billing.cycle(session.getCycleId());
        var outcome = attendance.mark(session.getStatus(), result, session.getStartsAt(), cycle.status() == CycleStatus.ACTIVE);
        if (outcome.consumesClass()) {
            billing.consumeClass(session.getCycleId());                      // before the status changes: it is still "pending"
        }
        session.mark(outcome.newStatus(), coachUserId, clock.instant());
        return summary(sessions.saveAndFlush(session), names());
    }

    // =================================================================================================
    // helpers
    // =================================================================================================

    private ClassSession persistNew(ClassSession session) {
        try {
            return sessions.saveAndFlush(session);
        } catch (DataIntegrityViolationException e) {
            // The exclusion constraint caught a race that the checks above could not see.
            throw new SchedulingRuleException(SchedulingRuleException.Code.SLOT_TAKEN, "That time is already taken");
        }
    }

    private static CycleSnapshot snapshot(CycleSummary c, int scheduled) {
        return new CycleSnapshot(c.status() == CycleStatus.ACTIVE, c.endDate(), c.classesIncluded(), c.classesUsed(), scheduled);
    }

    private List<Window> windows() {
        return availability.findAllByOrderByDayOfWeekAscStartTimeAsc().stream()
                .map(r -> new Window(DayOfWeek.of(r.getDayOfWeek()), r.getStartTime(), r.getEndTime())).toList();
    }

    private List<Range> blockRanges(Instant from, Instant to) {
        return blocks.findOverlapping(from, to).stream().map(b -> new Range(b.getStartsAt(), b.getEndsAt())).toList();
    }

    /** SCHEDULED classes overlapping [from, to), optionally leaving one out (the class being replaced). */
    private List<Range> bookedRanges(Instant from, Instant to, UUID except) {
        return sessions.findScheduledOverlapping(from, to).stream()
                .filter(s -> !s.getId().equals(except))
                .map(s -> new Range(s.getStartsAt(), s.getEndsAt())).toList();
    }

    private List<SlotView> freeSlots(LocalDate from, LocalDate to, Instant notBefore, SchedulingSettings given) {
        SchedulingSettings settings = given != null ? given : coaches.schedulingSettings(TenantContext.get());
        Duration duration = Duration.ofMinutes(settings.classDurationMinutes());
        Instant start = startOf(from);
        Instant end = startOf(to.plusDays(1));
        List<Range> blockList = blocks.findOverlapping(start, end).stream().map(b -> new Range(b.getStartsAt(), b.getEndsAt())).toList();
        List<Range> booked = sessions.findScheduledOverlapping(start, end).stream().map(s -> new Range(s.getStartsAt(), s.getEndsAt())).toList();
        return calendar.freeSlots(from, to, windows(), duration, blockList, booked, notBefore).stream().map(r -> {
            var local = r.start().atZone(calendar.zone());
            return new SlotView(r.start(), r.end(), local.toLocalDate().toString(), local.toLocalTime().format(HH_MM));
        }).toList();
    }

    private Instant startOf(LocalDate date) {
        return date.atStartOfDay(calendar.zone()).toInstant();
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RANGE");
        }
    }

    private Map<UUID, String> names() {
        return students.list().stream().collect(Collectors.toMap(StudentSummary::id, StudentSummary::fullName));
    }

    static SessionSummary summary(ClassSession s, Map<UUID, String> names) {
        return new SessionSummary(s.getId(), s.getStudentId(), names.get(s.getStudentId()), s.getCycleId(), s.getStartsAt(),
                s.getEndsAt(), s.getStatus(), s.getRescheduledFrom(), s.getCancelReason());
    }
}
