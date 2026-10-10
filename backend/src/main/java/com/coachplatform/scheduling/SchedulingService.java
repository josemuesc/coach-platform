package com.coachplatform.scheduling;

import com.coachplatform.billing.BillingService;
import com.coachplatform.billing.api.CycleStatus;
import com.coachplatform.billing.api.CycleSummary;
import com.coachplatform.billing.api.Modality;
import com.coachplatform.coach.CoachService;
import com.coachplatform.coach.api.SchedulingSettings;
import com.coachplatform.common.ApiException;
import com.coachplatform.common.ConcurrentChangeException;
import com.coachplatform.common.ConflictRetry;
import com.coachplatform.scheduling.api.ActorRole;
import com.coachplatform.scheduling.api.AffectedStudent;
import com.coachplatform.scheduling.api.AgendaView;
import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.api.AttendanceView;
import com.coachplatform.scheduling.api.AuditAction;
import com.coachplatform.scheduling.api.AuditMethod;
import com.coachplatform.scheduling.api.CancelAttendancesResult;
import com.coachplatform.scheduling.api.CancelResult;
import com.coachplatform.scheduling.api.EventCancelResult;
import com.coachplatform.scheduling.api.EventStatus;
import com.coachplatform.scheduling.api.EventView;
import com.coachplatform.scheduling.api.MarkItem;
import com.coachplatform.scheduling.api.SlotView;
import com.coachplatform.scheduling.api.StudentSlotView;
import com.coachplatform.scheduling.domain.BookingRules;
import com.coachplatform.scheduling.domain.BookingRules.Action;
import com.coachplatform.scheduling.domain.BookingRules.CycleSnapshot;
import com.coachplatform.scheduling.domain.BookingRules.Decision;
import com.coachplatform.scheduling.domain.CancellationPolicy;
import com.coachplatform.scheduling.domain.EventRules;
import com.coachplatform.scheduling.domain.EventSnapshot;
import com.coachplatform.scheduling.domain.SchedulingRuleException;
import com.coachplatform.scheduling.domain.SlotCalendar;
import com.coachplatform.scheduling.domain.SlotCalendar.Range;
import com.coachplatform.scheduling.domain.SlotCalendar.Window;
import com.coachplatform.scheduling.domain.SlotCatalog;
import com.coachplatform.students.StudentService;
import com.coachplatform.students.api.StudentSummary;
import com.coachplatform.tenant.TenantContext;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Supplier;

/**
 * Public API of scheduling. An EVENT is the coach's time slot (shared by several students when it is semi-personalized);
 * an ATTENDANCE is one student's place in it. Cycle consumption, the cancellation window and marking are per attendance.
 *
 * <p>GLOBAL LOCK ORDER (never violated, it is what keeps this deadlock-free):
 * <ol>
 *   <li>the STUDENT rows involved (several students: ascending id),</li>
 *   <li>the coach's CALENDAR - only when a new event may be created: read first, and only if nothing is there take the
 *       calendar lock and check again before creating,</li>
 *   <li>the EVENT rows involved (ascending id), then cycle / attendance rows.</li>
 * </ol>
 * Anything that has to be re-read after locking is loaded only AFTER the lock (ids first, via projection queries), so
 * the persistence context never hands back a stale copy.
 */
@Service
public class SchedulingService {

    private static final int MAX_RANGE_DAYS = 62;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final StudentService students;
    private final BillingService billing;
    private final CoachService coaches;
    private final ClassSessionRepository events;
    private final SessionAttendanceRepository attendances;
    private final AvailabilityRuleRepository availability;
    private final AvailabilityBlockRepository blocks;
    private final SchedulingViews views;
    private final BookingRules bookingRules;
    private final CancellationPolicy cancellation;
    private final AttendanceMarker marker;
    private final AttendanceAuditWriter audit;
    private final EventRules eventRules;
    private final SlotCalendar calendar;
    private final Clock clock;
    private final TransactionTemplate tx;

    SchedulingService(StudentService students, BillingService billing, CoachService coaches, ClassSessionRepository events,
                      SessionAttendanceRepository attendances, AvailabilityRuleRepository availability,
                      AvailabilityBlockRepository blocks, SchedulingViews views, BookingRules bookingRules,
                      CancellationPolicy cancellation, AttendanceMarker marker, AttendanceAuditWriter audit, EventRules eventRules,
                      SlotCalendar calendar, Clock clock, TransactionTemplate tx) {
        this.tx = tx;
        this.students = students;
        this.billing = billing;
        this.coaches = coaches;
        this.events = events;
        this.attendances = attendances;
        this.availability = availability;
        this.blocks = blocks;
        this.views = views;
        this.bookingRules = bookingRules;
        this.cancellation = cancellation;
        this.marker = marker;
        this.audit = audit;
        this.eventRules = eventRules;
        this.calendar = calendar;
        this.clock = clock;
    }

    // =================================================================================================
    // reads
    // =================================================================================================

    /** The coach's agenda for local dates [from, to]: every scheduled event with its attendees and free seats, plus the empty blocks. */
    @Transactional(readOnly = true)
    public AgendaView agenda(LocalDate from, LocalDate to) {
        checkRange(from, to);
        Instant start = startOf(from);
        Instant end = startOf(to.plusDays(1));
        List<ClassSession> scheduled = events.findByStatusAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAt(
                EventStatus.SCHEDULED, start, end);
        SchedulingSettings settings = coaches.schedulingSettings(TenantContext.get());
        List<Range> taken = events.findScheduledOverlapping(start, end).stream().map(e -> new Range(e.getStartsAt(), e.getEndsAt())).toList();
        List<SlotView> free = calendar.freeSlots(from, to, windows(), Duration.ofMinutes(settings.classDurationMinutes()),
                blockRanges(start, end), taken, clock.instant()).stream().map(r -> {
                    var local = r.start().atZone(calendar.zone());
                    return new SlotView(r.start(), r.end(), local.toLocalDate().toString(), local.toLocalTime().format(HH_MM));
                }).toList();
        return new AgendaView(views.eventViews(scheduled), free);
    }

    /**
     * What a student can take: each block with its modality and "occupied of capacity", filtered by the student's plan,
     * with the booking lead time applied and limited to the active cycle. Nobody else is identified.
     */
    @Transactional(readOnly = true)
    public List<StudentSlotView> slotsForStudent(UUID studentUserId, LocalDate from, LocalDate to) {
        checkRange(from, to);
        StudentSummary student = students.findByUserId(studentUserId);
        CycleSummary cycle = billing.activeCycle(student.id()).orElse(null);
        if (cycle == null) {
            return List.of();
        }
        SchedulingSettings settings = coaches.schedulingSettings(TenantContext.get());
        LocalDate lastDay = to.isAfter(cycle.endDate()) ? cycle.endDate() : to;
        if (lastDay.isBefore(from)) {
            return List.of();
        }
        Instant start = startOf(from);
        Instant end = startOf(lastDay.plusDays(1));
        Instant notBefore = clock.instant().plus(Duration.ofHours(settings.cancelWindowHours())).minusSeconds(1);
        List<Range> grid = calendar.freeSlots(from, lastDay, windows(), Duration.ofMinutes(settings.classDurationMinutes()),
                blockRanges(start, end), List.of(), notBefore);

        List<ClassSession> inRange = events.findScheduledOverlapping(start, end);
        Map<UUID, Integer> counts = views.liveCounts(inRange.stream().map(ClassSession::getId).toList());
        List<SlotCatalog.EventInfo> infos = inRange.stream().map(e -> new SlotCatalog.EventInfo(e.getId(),
                new Range(e.getStartsAt(), e.getEndsAt()), e.getModality(), e.getCapacity(), counts.getOrDefault(e.getId(), 0))).toList();

        return SlotCatalog.forStudent(cycle.modality(), settings.defaultGroupCapacity(), grid, infos).stream().map(s -> {
            var local = s.range().start().atZone(calendar.zone());
            return new StudentSlotView(s.range().start(), s.range().end(), local.toLocalDate().toString(),
                    local.toLocalTime().format(HH_MM), s.modality(), s.capacity(), s.occupied(), s.eventId());
        }).toList();
    }

    /** Booked places whose event already started and are still unmarked. */
    @Transactional(readOnly = true)
    public List<AttendanceView> pending() {
        return views.attendanceViews(attendances.findPending(clock.instant()), true);
    }

    @Transactional(readOnly = true)
    public List<AttendanceView> attendancesOfStudent(UUID studentId) {
        students.get(studentId); // 404 for a student of another tenant
        return views.attendanceViews(attendances.findByStudentIdOrderByCreatedAtDesc(studentId), true);
    }

    /** The logged-in student's own places. The student comes from the token, never from a request parameter. */
    @Transactional(readOnly = true)
    public List<AttendanceView> myAttendances(UUID studentUserId) {
        StudentSummary student = students.findByUserId(studentUserId);
        return views.attendanceViews(attendances.findByStudentIdOrderByCreatedAtDesc(student.id()), false);
    }

    // =================================================================================================
    // booking
    // =================================================================================================

    public AttendanceView bookAsStudent(UUID studentUserId, Instant startsAt) {
        return inFreshTransactions(() -> {
            StudentSummary student = students.findByUserId(studentUserId);
            return book(student.id(), startsAt, studentUserId, true, null, false);
        });
    }

    /** The coach books a student; with override=true (and a reason) the student can be put in a full event or one of the other modality. */
    public AttendanceView bookAsCoach(UUID studentId, Instant startsAt, UUID coachUserId, boolean override, String overrideReason) {
        return inFreshTransactions(() -> book(studentId, startsAt, coachUserId, false, overrideReason, override));
    }

    private AttendanceView book(UUID studentId, Instant startsAt, UUID byUser, boolean byStudent, String overrideReason, boolean override) {
        students.lockForUpdate(studentId);                                              // 1. the student
        SchedulingSettings settings = coaches.schedulingSettings(TenantContext.get());
        CycleSummary cycle = billing.activeCycle(studentId).orElse(null);
        Placement placement = plan(studentId, cycle, startsAt, byUser, byStudent, true, override, overrideReason, settings, null);
        SessionAttendance created = commit(placement, studentId, cycle, null, byUser, byStudent, overrideReason);
        return views.attendanceView(created, !byStudent);
    }

    // =================================================================================================
    // cancelling and rescheduling ONE student's place
    // =================================================================================================

    /**
     * A student cancels (and optionally moves) their own place. It only affects that place: the event goes on while others
     * remain. Free only with enough notice; with a new date the change is atomic - if the new slot is not valid, nothing happens.
     */
    public CancelResult cancelAsStudent(UUID studentUserId, UUID attendanceId, Instant newStartsAt) {
        return inFreshTransactions(() -> doCancelAsStudent(studentUserId, attendanceId, newStartsAt));
    }

    private CancelResult doCancelAsStudent(UUID studentUserId, UUID attendanceId, Instant newStartsAt) {
        StudentSummary student = students.findByUserId(studentUserId);
        students.lockForUpdate(student.id());                                           // 1. the student
        SessionAttendance place = attendances.findById(attendanceId).orElseThrow(AttendanceNotFoundException::new);   // read under the lock
        if (!place.getStudentId().equals(student.id())) {
            throw new AttendanceNotFoundException();                                    // someone else's place: reveal nothing
        }
        ClassSession event = events.findById(place.getSessionId()).orElseThrow(EventNotFoundException::new);   // startsAt never changes
        SchedulingSettings settings = coaches.schedulingSettings(TenantContext.get());
        cancellation.requireStudentMayCancel(place.getStatus(), event.getStartsAt(), settings.cancelWindowHours());
        return removeAndMaybeReplace(place, event.getId(), newStartsAt, true, studentUserId, null, settings, false, null);
    }

    /**
     * The coach cancels ONE student's place: always allowed (no window, even after the event started), reason mandatory,
     * never deducts a class. This is also how a late student cancellation is forgiven. With a new date the replacement is atomic.
     */
    public CancelResult cancelAsCoach(UUID attendanceId, String reason, Instant newStartsAt, boolean override, String overrideReason,
                                      UUID coachUserId) {
        return inFreshTransactions(() -> doCancelAsCoach(attendanceId, reason, newStartsAt, override, overrideReason, coachUserId));
    }

    private CancelResult doCancelAsCoach(UUID attendanceId, String reason, Instant newStartsAt, boolean override, String overrideReason,
                                         UUID coachUserId) {
        UUID studentId = ownerOf(attendanceId);
        students.lockForUpdate(studentId);                                              // 1. the student
        SessionAttendance place = attendances.findById(attendanceId).orElseThrow(AttendanceNotFoundException::new);   // read under the lock
        cancellation.requireCoachMayCancel(place.getStatus(), reason);
        SchedulingSettings settings = coaches.schedulingSettings(TenantContext.get());
        return removeAndMaybeReplace(place, place.getSessionId(), newStartsAt, false, coachUserId, reason.trim(), settings, override, overrideReason);
    }

    private CancelResult removeAndMaybeReplace(SessionAttendance place, UUID eventId, Instant newStartsAt, boolean byStudent,
                                               UUID byUser, String reason, SchedulingSettings settings, boolean override,
                                               String overrideReason) {
        Instant now = clock.instant();
        AttendanceStatus plain = byStudent ? AttendanceStatus.CANCELLED_ON_TIME : AttendanceStatus.CANCELLED_BY_COACH;

        if (newStartsAt == null) {
            ClassSession event = events.findByIdForUpdate(eventId).orElseThrow(EventNotFoundException::new);   // 3. the event
            cancelPlain(place, event, plain, byStudent, byUser, reason, now);
            return new CancelResult(views.attendanceView(place, !byStudent), null);
        }

        // Validate the NEW place before touching the original: any failure leaves the original place untouched.
        CycleSummary cycle = billing.cycle(place.getCycleId());
        Placement placement = plan(place.getStudentId(), cycle, newStartsAt, byUser, byStudent, false, override, overrideReason, settings, eventId);

        ClassSession old = placement.locked().get(eventId);
        AttendanceStatus moved = byStudent ? AttendanceStatus.RESCHEDULED : AttendanceStatus.CANCELLED_BY_COACH;
        place.cancel(moved, byUser, reason, now);
        attendances.saveAndFlush(place);
        // the student's move is a RESCHEDULE; the coach's is a CANCEL of the old place (plus a BOOK of the new one)
        audit.record(place, byStudent ? AuditAction.RESCHEDULE : AuditAction.CANCEL, AttendanceStatus.SCHEDULED, moved,
                methodOf(byStudent), byUser, roleOf(byStudent), reason);
        cancelEventIfEmpty(old, byUser);    // flushed BEFORE a new event is created: the new one may overlap the old one's time
        SessionAttendance replacement = commit(placement, place.getStudentId(), cycle, place.getId(), byUser, byStudent, overrideReason);
        return new CancelResult(views.attendanceView(place, !byStudent), views.attendanceView(replacement, !byStudent));
    }

    /** The one way a place is cancelled WITHOUT a replacement (one student's cancellation, one coach's, or each place of a bulk cancel). */
    void cancelPlain(SessionAttendance place, ClassSession lockedEvent, AttendanceStatus plain, boolean byStudent, UUID byUser,
                             String reason, Instant now) {
        place.cancel(plain, byUser, reason, now);
        attendances.saveAndFlush(place);
        audit.record(place, AuditAction.CANCEL, AttendanceStatus.SCHEDULED, plain, methodOf(byStudent), byUser, roleOf(byStudent), reason);
        cancelEventIfEmpty(lockedEvent, byUser);
    }

    /**
     * The coach cancels several of ONE student's booked classes at once (the way out of a modality conflict on renewal). ALL OR NOTHING:
     * if any place is not the student's, not found, or not SCHEDULED, nothing is cancelled. Same rules, same audit line (one CANCEL per
     * class) and same lock order as the individual cancellation: the student first, then the events in ascending id.
     */
    public CancelAttendancesResult cancelManyAsCoach(UUID studentId, List<UUID> attendanceIds, String reason, UUID coachUserId) {
        return inFreshTransactions(() -> doCancelManyAsCoach(studentId, attendanceIds, reason, coachUserId));
    }

    private CancelAttendancesResult doCancelManyAsCoach(UUID studentId, List<UUID> attendanceIds, String reason, UUID coachUserId) {
        students.get(studentId);                                                        // 404 for a student of another tenant
        students.lockForUpdate(studentId);                                              // 1. the student
        List<UUID> ids = attendanceIds.stream().distinct().toList();
        List<SessionAttendance> places = new ArrayList<>();
        for (UUID id : ids) {
            SessionAttendance place = attendances.findById(id).orElseThrow(AttendanceNotFoundException::new);   // read under the lock
            if (!place.getStudentId().equals(studentId)) {
                throw new AttendanceNotFoundException();                                // another student's place: reveal nothing
            }
            cancellation.requireCoachMayCancel(place.getStatus(), reason);
            places.add(place);
        }
        Map<UUID, ClassSession> lockedEvents = new java.util.LinkedHashMap<>();
        for (UUID eventId : new TreeSet<>(places.stream().map(SessionAttendance::getSessionId).toList())) {
            lockedEvents.put(eventId, events.findByIdForUpdate(eventId).orElseThrow(EventNotFoundException::new));   // 3. the events, ascending id
        }
        Instant now = clock.instant();
        for (SessionAttendance place : places) {
            cancelPlain(place, lockedEvents.get(place.getSessionId()), AttendanceStatus.CANCELLED_BY_COACH, false, coachUserId, reason.trim(), now);
        }
        return new CancelAttendancesResult(views.attendanceViews(places, true));
    }

    /** An event with no live place left (booked or seen) is cancelled by the system; one with a marked class never is. */
    private void cancelEventIfEmpty(ClassSession event, UUID byUser) {
        if (event.getStatus() == EventStatus.SCHEDULED && EventRules.shouldAutoCancel(attendances.countLive(event.getId()))) {
            event.cancel(byUser, "NO_ACTIVE_ATTENDANCES", clock.instant());
            events.saveAndFlush(event);
        }
    }

    // =================================================================================================
    // the event as a whole
    // =================================================================================================

    /**
     * The coach cancels the whole event: every booked place becomes CANCELLED_BY_COACH and nobody loses a class. Places already
     * marked (attended / no-show) stay as they are, and then the event itself stays too. Returns the students affected.
     */
    public EventCancelResult cancelEvent(UUID eventId, String reason, UUID coachUserId) {
        return inFreshTransactions(() -> doCancelEvent(eventId, reason, coachUserId));
    }

    private EventCancelResult doCancelEvent(UUID eventId, String reason, UUID coachUserId) {
        events.findById(eventId).orElseThrow(EventNotFoundException::new);
        List<UUID> studentIds = new ArrayList<>(new TreeSet<>(attendances.findLiveStudentIdsBySession(eventId)));
        for (UUID id : studentIds) {
            students.lockForUpdate(id);                                                 // 1. the students, ascending id
        }
        ClassSession event = events.findByIdForUpdate(eventId).orElseThrow(EventNotFoundException::new);   // 3. the event
        eventRules.requireCoachMayCancelEvent(event.getStatus(), reason);

        List<SessionAttendance> places = attendances.findBySessionId(eventId);
        Set<UUID> locked = new HashSet<>(studentIds);
        if (places.stream().anyMatch(a -> a.getStatus().isLive() && !locked.contains(a.getStudentId()))) {
            throw new ConcurrentChangeException();                                      // someone joined meanwhile: the whole operation is retried
        }
        Map<UUID, String> names = views.studentNames();
        List<AffectedStudent> affected = new ArrayList<>();
        Instant now = clock.instant();
        for (SessionAttendance a : places) {
            if (a.getStatus() == AttendanceStatus.SCHEDULED) {
                a.cancel(AttendanceStatus.CANCELLED_BY_COACH, coachUserId, reason.trim(), now);
                audit.record(a, AuditAction.CANCEL, AttendanceStatus.SCHEDULED, AttendanceStatus.CANCELLED_BY_COACH, AuditMethod.COACH,
                        coachUserId, ActorRole.COACH, reason.trim());
                affected.add(new AffectedStudent(a.getStudentId(), names.get(a.getStudentId()), a.getId()));
            }
        }
        attendances.saveAllAndFlush(places);
        cancelEventIfEmpty(event, coachUserId);
        return new EventCancelResult(views.eventView(event), affected);
    }

    /** The coach changes the capacity of one semi-personalized event, before it starts, never below the people already in it. */
    @Transactional
    public EventView changeCapacity(UUID eventId, int capacity) {
        ClassSession event = events.findByIdForUpdate(eventId).orElseThrow(EventNotFoundException::new);   // the event row
        eventRules.requireCapacityChange(event.getModality(), event.getStatus(), event.getStartsAt(),
                attendances.countLive(eventId), capacity);
        event.setCapacity(capacity);
        events.saveAndFlush(event);
        return views.eventView(event);
    }

    // =================================================================================================
    // attendance
    // =================================================================================================

    /** The coach marks ONE place ATTENDED or NO_SHOW. Marking uses up one class of that student's cycle and cannot be undone. */
    @Transactional
    public AttendanceView markAttendance(UUID attendanceId, AttendanceStatus result, UUID coachUserId) {
        UUID studentId = ownerOf(attendanceId);
        students.lockForUpdate(studentId);                                              // 1. the student
        SessionAttendance place = attendances.findById(attendanceId).orElseThrow(AttendanceNotFoundException::new);   // read under the lock
        markOne(place, result, coachUserId);
        return views.attendanceView(place, true);
    }

    /**
     * The coach marks several places of ONE event at once. All or nothing: one transaction, the students locked in ascending
     * id order, and any invalid mark rolls back every other one.
     */
    @Transactional
    public List<AttendanceView> markEvent(UUID eventId, List<MarkItem> items, UUID coachUserId) {
        events.findById(eventId).orElseThrow(EventNotFoundException::new);
        Set<UUID> seen = new HashSet<>();
        TreeSet<UUID> studentIds = new TreeSet<>();
        for (MarkItem item : items) {
            if (!seen.add(item.attendanceId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DUPLICATE_ATTENDANCE");
            }
            Object[] row = attendances.findOwnerAndSessionById(item.attendanceId()).stream().findFirst()
                    .orElseThrow(AttendanceNotFoundException::new);
            if (!eventId.equals(row[1])) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ATTENDANCE_NOT_IN_EVENT");
            }
            studentIds.add((UUID) row[0]);
        }
        for (UUID id : studentIds) {
            students.lockForUpdate(id);                                                 // 1. the students, ascending id
        }
        List<SessionAttendance> marked = new ArrayList<>();
        for (MarkItem item : items) {
            SessionAttendance place = attendances.findById(item.attendanceId()).orElseThrow(AttendanceNotFoundException::new);
            markOne(place, item.status(), coachUserId);
            marked.add(place);
        }
        return views.attendanceViews(marked, true);
    }

    private void markOne(SessionAttendance place, AttendanceStatus target, UUID coachUserId) {
        marker.mark(place, target, coachUserId, ActorRole.COACH, AuditMethod.COACH);
    }

    // =================================================================================================
    // placing a student in an event (create it or join it)
    // =================================================================================================

    /** A validated decision plus the event rows already locked for it (the event to join, and an event being left). */
    private record Placement(Decision decision, ClassSession joinEvent, Map<UUID, ClassSession> locked) {
    }

    /**
     * Reads, locks and validates; writes nothing. Reads the overlapping events (ids only); if there are none it takes the
     * coach's calendar lock and looks again before deciding to create; then locks the event rows in ascending id order.
     *
     * @param leavingEventId the event the student is moving out of (reschedule), or null
     */
    private Placement plan(UUID studentId, CycleSummary cycle, Instant startsAt, UUID byUser, boolean byStudent, boolean consumesQuota,
                           boolean override, String overrideReason, SchedulingSettings settings, UUID leavingEventId) {
        Duration duration = Duration.ofMinutes(settings.classDurationMinutes());
        Instant endsAt = startsAt.plus(duration);

        List<UUID> ids = events.findScheduledOverlappingIds(startsAt, endsAt);
        if (ids.isEmpty() || leavingEventId != null) {
            // 2. Nothing there (a new event may be created) - or a reschedule, where emptying the old event can also end in a
            //    new one: take the calendar lock BEFORE any event lock, then check again before deciding.
            coaches.lockCalendar(TenantContext.get());
            ids = events.findScheduledOverlappingIds(startsAt, endsAt);
        }
        TreeSet<UUID> toLock = new TreeSet<>(ids);
        if (leavingEventId != null) {
            toLock.add(leavingEventId);
        }
        Map<UUID, ClassSession> locked = new HashMap<>();
        for (UUID id : toLock) {                                                        // 3. event rows, ascending id
            locked.put(id, events.findByIdForUpdate(id).orElseThrow(EventNotFoundException::new));
        }
        if (ids.stream().anyMatch(id -> locked.get(id).getStatus() != EventStatus.SCHEDULED)) {
            throw new ConcurrentChangeException();                                      // cancelled between the read and the lock: the whole operation is retried
        }

        Map<UUID, Integer> counts = views.liveCounts(ids);
        List<EventSnapshot> snapshots = new ArrayList<>();
        ClassSession only = null;
        for (UUID id : ids) {
            ClassSession e = locked.get(id);
            int occupied = counts.getOrDefault(id, 0);
            boolean mine = attendances.existsLive(id, studentId);
            boolean sameSlot = e.getStartsAt().equals(startsAt) && e.getEndsAt().equals(endsAt);
            if (id.equals(leavingEventId) && mine && occupied == 1 && !sameSlot) {
                continue;   // the student is the only one in it: leaving empties it and it is cancelled, so it must not block the new place
            }               // (moving to the very same slot is pointless: it stays in the list and is refused as ALREADY_BOOKED)
            snapshots.add(new EventSnapshot(new Range(e.getStartsAt(), e.getEndsAt()), e.getModality(), e.getCapacity(), occupied, mine));
            only = e;
        }
        CycleSnapshot snapshot = cycle == null ? null : new CycleSnapshot(cycle.status() == CycleStatus.ACTIVE, cycle.endDate(),
                cycle.classesIncluded(), cycle.classesUsed(), attendances.countByCycleIdAndStatus(cycle.id(), AttendanceStatus.SCHEDULED),
                cycle.modality());
        BookingRules.Override rule = override ? new BookingRules.Override(overrideReason) : null;
        Decision decision = bookingRules.validate(new BookingRules.Request(startsAt, duration, snapshot, windows(),
                blockRanges(startsAt, endsAt), snapshots, settings.defaultGroupCapacity(), byStudent, settings.cancelWindowHours(),
                consumesQuota, rule));
        return new Placement(decision, decision.action() == Action.JOIN_EVENT ? only : null, locked);
    }

    /** Writes what {@link #plan} decided: the event when it has to be created, and the student's place in it. */
    private SessionAttendance commit(Placement placement, UUID studentId, CycleSummary cycle, UUID rescheduledFrom, UUID byUser,
                                     boolean byStudent, String overrideReason) {
        Decision d = placement.decision();
        ClassSession event = placement.joinEvent();
        try {
            if (d.action() == Action.CREATE_EVENT) {
                event = events.saveAndFlush(new ClassSession(d.range().start(), d.range().end(), d.eventModality(), d.eventCapacity(), byUser));
            }
            SessionAttendance place = new SessionAttendance(event.getId(), studentId, cycle.id(), rescheduledFrom, byUser);
            if (d.overridden()) {
                place.markOverride(byUser, overrideReason.trim());
            }
            SessionAttendance saved = attendances.saveAndFlush(place);
            audit.record(saved, AuditAction.BOOK, null, AttendanceStatus.SCHEDULED, methodOf(byStudent), byUser, roleOf(byStudent),
                    d.overridden() ? overrideReason.trim() : null);   // an exception leaves its reason in the audit line too
            return saved;
        } catch (DataIntegrityViolationException e) {
            // The exclusion constraint / unique index caught what the checks above could not see.
            throw new SchedulingRuleException(SchedulingRuleException.Code.SLOT_TAKEN, "That time is already taken");
        }
    }

    // =================================================================================================
    // helpers
    // =================================================================================================

    /**
     * Runs the work in its OWN transaction and, if it fails with {@link ConcurrentChangeException} (a read went stale before
     * it could be locked), repeats it from scratch - at most 3 attempts with a short pause - before the 409 reaches the client.
     * Each attempt is a new transaction, so the public methods that use it are deliberately not {@code @Transactional}.
     */
    private <T> T inFreshTransactions(Supplier<T> work) {
        return ConflictRetry.run(() -> tx.execute(status -> work.get()));
    }

    /** The owner of a place, read without loading the entity (so the student can be locked before the place is read). */
    private UUID ownerOf(UUID attendanceId) {
        Object[] row = attendances.findOwnerAndSessionById(attendanceId).stream().findFirst()
                .orElseThrow(AttendanceNotFoundException::new);
        return (UUID) row[0];
    }

    private static AuditMethod methodOf(boolean byStudent) {
        return byStudent ? AuditMethod.STUDENT : AuditMethod.COACH;
    }

    private static ActorRole roleOf(boolean byStudent) {
        return byStudent ? ActorRole.STUDENT : ActorRole.COACH;
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

    private static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RANGE");
        }
    }
}
