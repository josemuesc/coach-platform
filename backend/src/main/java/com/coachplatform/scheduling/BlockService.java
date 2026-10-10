package com.coachplatform.scheduling;

import com.coachplatform.billing.BillingService;
import com.coachplatform.billing.api.CycleStatus;
import com.coachplatform.billing.api.CycleSummary;
import com.coachplatform.coach.CoachService;
import com.coachplatform.common.ApiException;
import com.coachplatform.common.ConcurrentChangeException;
import com.coachplatform.common.ConflictRetry;
import com.coachplatform.scheduling.api.AffectedClass;
import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.api.BlockCreated;
import com.coachplatform.scheduling.api.BlockInput;
import com.coachplatform.scheduling.api.BlockPreview;
import com.coachplatform.scheduling.api.EventStatus;
import com.coachplatform.scheduling.api.StudentImpact;
import com.coachplatform.scheduling.domain.BlockRules;
import com.coachplatform.scheduling.domain.BlockRules.Effect;
import com.coachplatform.scheduling.domain.BookingRules;
import com.coachplatform.scheduling.domain.CycleRisk;
import com.coachplatform.scheduling.domain.SlotCalendar.Range;
import com.coachplatform.students.StudentService;
import com.coachplatform.students.api.StudentSummary;
import com.coachplatform.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creating a block of the coach's calendar. A block RELEASES the classes already booked inside it that have not started: they are
 * cancelled by the coach (no class is deducted, the student can book again), with one CANCEL audit line each carrying the block's
 * reason, in the SAME transaction as the block: all or nothing. Classes already marked, or started and waiting to be marked, are
 * never touched.
 *
 * <p>The coach confirms the list first (preview) and sends it back when saving; if the real list changed meanwhile nothing is saved
 * ({@code BLOCK_AFFECTED_CHANGED}). Lock order (the project's): the students involved by ascending id, then the coach's calendar,
 * then the events by ascending id; the places are read only AFTER the locks, so a booking that races with the block either is seen
 * by it or is retried and sees the block.
 */
@Service
public class BlockService {

    private final ClassSessionRepository events;
    private final SessionAttendanceRepository attendances;
    private final AvailabilityBlockRepository blocks;
    private final StudentService students;
    private final BillingService billing;
    private final CoachService coaches;
    private final SchedulingService scheduling;
    private final CoachAgendaService agenda;
    private final BlockRules rules;
    private final BlockViews blockViews;
    private final TransactionTemplate tx;
    private final Clock clock;

    BlockService(ClassSessionRepository events, SessionAttendanceRepository attendances, AvailabilityBlockRepository blocks,
                 StudentService students, BillingService billing, CoachService coaches, SchedulingService scheduling,
                 CoachAgendaService agenda, BlockRules rules, BlockViews blockViews, TransactionTemplate tx, Clock clock) {
        this.events = events;
        this.attendances = attendances;
        this.blocks = blocks;
        this.students = students;
        this.billing = billing;
        this.coaches = coaches;
        this.scheduling = scheduling;
        this.agenda = agenda;
        this.rules = rules;
        this.blockViews = blockViews;
        this.tx = tx;
        this.clock = clock;
    }

    /** What saving this block would do. Writes nothing and takes no locks. */
    @Transactional(readOnly = true)
    public BlockPreview preview(BlockInput input) {
        Range range = resolve(input);
        List<UUID> ids = events.findScheduledOverlappingIds(range.start(), range.end());
        Map<UUID, ClassSession> byId = events.findAllById(ids).stream().collect(Collectors.toMap(ClassSession::getId, Function.identity()));
        return inspect(attendances.findLiveBySessions(ids), byId).preview();
    }

    /** Not {@code @Transactional}: each attempt opens its own transaction (see {@link ConflictRetry}). */
    public BlockCreated create(BlockInput input, UUID coachUserId) {
        return ConflictRetry.run(() -> tx.execute(status -> doCreate(input, coachUserId)));
    }

    private BlockCreated doCreate(BlockInput input, UUID coachUserId) {
        Range range = resolve(input);

        // 1. the students that may be released, ascending id (ids only: nothing is loaded before the locks)
        TreeSet<UUID> studentIds = new TreeSet<>();
        for (UUID eventId : events.findScheduledOverlappingIds(range.start(), range.end())) {
            studentIds.addAll(attendances.findLiveStudentIdsBySession(eventId));
        }
        for (UUID id : studentIds) {
            students.lockForUpdate(id);
        }
        // 2. the calendar: a booking that CREATES an event in this range is serialized with the block
        coaches.lockCalendar(TenantContext.get());
        // 3. the events, ascending id, then look again: what was read before the locks may be stale
        List<UUID> eventIds = events.findScheduledOverlappingIds(range.start(), range.end());
        Map<UUID, ClassSession> locked = new LinkedHashMap<>();
        for (UUID id : new TreeSet<>(eventIds)) {
            ClassSession event = events.findByIdForUpdate(id).orElseThrow(EventNotFoundException::new);
            if (event.getStatus() != EventStatus.SCHEDULED) {
                throw new ConcurrentChangeException();
            }
            locked.put(id, event);
        }
        List<SessionAttendance> places = attendances.findLiveBySessions(eventIds);
        if (places.stream().anyMatch(p -> !studentIds.contains(p.getStudentId()))) {
            throw new ConcurrentChangeException();   // someone joined meanwhile: the whole operation is repeated and sees them
        }

        Inspection inspection = inspect(places, locked);
        Set<UUID> actual = inspection.released().stream().map(SessionAttendance::getId).collect(Collectors.toSet());
        if (!actual.equals(new HashSet<>(input.affectedAttendanceIds()))) {
            throw new BlockAffectedChangedException(inspection.preview());   // nothing was written: the transaction rolls back
        }

        String reason = input.reason().trim();
        AvailabilityBlock block = blocks.saveAndFlush(new AvailabilityBlock(range.start(), range.end(), reason, coachUserId));
        Instant now = clock.instant();
        for (SessionAttendance place : inspection.released()) {
            scheduling.cancelPlain(place, locked.get(place.getSessionId()), AttendanceStatus.CANCELLED_BY_COACH, false, coachUserId,
                    "Bloqueo de agenda: " + reason, now);
        }
        return new BlockCreated(blockViews.summary(block), inspection.preview().affected(), impacts(inspection),
                inspection.preview().markedUntouched(), inspection.preview().pendingUntouched());
    }

    // ---------------------------------------------------------------------------------------------- internals

    private Range resolve(BlockInput input) {
        return rules.resolve(input.localDate(), input.allDay(), parse(input.startTime()), parse(input.endTime()));
    }

    private static LocalTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BLOCK");
        }
    }

    private record Inspection(List<SessionAttendance> released, BlockPreview preview, Map<UUID, StudentSummary> studentsById) {
    }

    private Inspection inspect(List<SessionAttendance> places, Map<UUID, ClassSession> eventsById) {
        Map<UUID, StudentSummary> byStudent = students.list().stream().collect(Collectors.toMap(StudentSummary::id, Function.identity()));
        List<SessionAttendance> released = new ArrayList<>();
        int marked = 0;
        int pending = 0;
        for (SessionAttendance p : places) {
            Effect effect = rules.effectOn(p.getStatus(), eventsById.get(p.getSessionId()).getStartsAt());
            switch (effect) {
                case RELEASE -> released.add(p);
                case KEEP_MARKED -> marked++;
                case KEEP_PENDING -> pending++;
                case NONE -> { }
            }
        }
        released.sort(Comparator.<SessionAttendance, Instant>comparing(p -> eventsById.get(p.getSessionId()).getStartsAt())
                .thenComparing(p -> byStudent.get(p.getStudentId()).fullName(), String.CASE_INSENSITIVE_ORDER));
        List<AffectedClass> affected = released.stream().map(p -> {
            ClassSession e = eventsById.get(p.getSessionId());
            StudentSummary s = byStudent.get(p.getStudentId());
            return new AffectedClass(p.getId(), s.id(), s.fullName(), s.minor(), e.getId(), e.getStartsAt(), e.getEndsAt(), e.getModality());
        }).toList();
        return new Inspection(released, new BlockPreview(affected, affected.size(), marked, pending), byStudent);
    }

    /** One entry per student whose classes were released, with the state AFTER the release. */
    private List<StudentImpact> impacts(Inspection inspection) {
        Map<UUID, List<SessionAttendance>> byStudent = inspection.released().stream()
                .collect(Collectors.groupingBy(SessionAttendance::getStudentId, LinkedHashMap::new, Collectors.toList()));
        List<StudentImpact> result = new ArrayList<>();
        byStudent.forEach((studentId, released) -> {
            StudentSummary student = inspection.studentsById().get(studentId);
            CycleSummary cycle = billing.cycle(released.get(0).getCycleId());
            boolean active = cycle.status() == CycleStatus.ACTIVE;
            int left = active ? BookingRules.classesAvailable(agenda.snapshot(cycle)) : 0;
            int free = active ? agenda.bookablePlacesUntilDeadline(studentId, cycle) : 0;
            boolean canExtend = billing.cycleOverview(studentId).extension().canExtendCycle();
            result.add(new StudentImpact(studentId, student.fullName(), student.minor(), released.size(), left, free,
                    active ? cycle.endDate() : null, active && CycleRisk.atRisk(left, free), canExtend));
        });
        return result;
    }
}
