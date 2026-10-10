package com.coachplatform.scheduling;

import com.coachplatform.billing.BillingService;
import com.coachplatform.billing.api.CycleStatus;
import com.coachplatform.coach.CoachService;
import com.coachplatform.coach.api.SchedulingSettings;
import com.coachplatform.scheduling.api.AttendanceView;
import com.coachplatform.scheduling.domain.AttendanceRules;
import com.coachplatform.scheduling.domain.CancellationPolicy;
import com.coachplatform.scheduling.domain.EventRules;
import com.coachplatform.tenant.TenantContext;
import com.coachplatform.scheduling.api.AttendeeView;
import com.coachplatform.scheduling.domain.ConfirmationRules;
import com.coachplatform.scheduling.api.EventStatus;
import com.coachplatform.scheduling.api.EventView;
import com.coachplatform.students.StudentService;
import com.coachplatform.students.api.StudentSummary;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Builds the API views from entities. Names of students are only ever put in views meant for the COACH. */
@Component
class SchedulingViews {

    private final ClassSessionRepository events;
    private final SessionAttendanceRepository attendances;
    private final StudentService students;
    private final BillingService billing;
    private final CoachService coaches;
    private final AttendanceRules attendanceRules;
    private final ConfirmationRules confirmation;
    private final EventRules eventRules;
    private final CancellationPolicy cancellation;

    SchedulingViews(ClassSessionRepository events, SessionAttendanceRepository attendances, StudentService students,
                    BillingService billing, CoachService coaches, AttendanceRules attendanceRules, ConfirmationRules confirmation,
                    EventRules eventRules, CancellationPolicy cancellation) {
        this.events = events;
        this.attendances = attendances;
        this.students = students;
        this.billing = billing;
        this.coaches = coaches;
        this.attendanceRules = attendanceRules;
        this.confirmation = confirmation;
        this.eventRules = eventRules;
        this.cancellation = cancellation;
    }

    private Map<UUID, StudentSummary> studentsById() {
        return students.list().stream().collect(Collectors.toMap(StudentSummary::id, Function.identity()));
    }

    Map<UUID, String> studentNames() {
        return students.list().stream().collect(Collectors.toMap(StudentSummary::id, StudentSummary::fullName));
    }

    /** Which of these cycles are active right now (effective state), read once per cycle. */
    private Map<UUID, Boolean> activeCycles(Collection<SessionAttendance> places) {
        Map<UUID, Boolean> active = new HashMap<>();
        for (SessionAttendance a : places) {
            active.computeIfAbsent(a.getCycleId(), id -> billing.cycle(id).status() == CycleStatus.ACTIVE);
        }
        return active;
    }

    /** Live places per event id (events without any live place are simply absent). */
    Map<UUID, Integer> liveCounts(Collection<UUID> eventIds) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (eventIds.isEmpty()) {
            return counts;
        }
        for (Object[] row : attendances.countLiveBySessions(eventIds)) {
            counts.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    /** Coach view of events with their live attendees. */
    List<EventView> eventViews(List<ClassSession> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, StudentSummary> byStudent = studentsById();
        SchedulingSettings settings = coaches.schedulingSettings(TenantContext.get());
        var live = attendances.findLiveBySessions(list.stream().map(ClassSession::getId).toList());
        Map<UUID, List<SessionAttendance>> bySession = live.stream().collect(Collectors.groupingBy(SessionAttendance::getSessionId));
        Map<UUID, Boolean> cycleActive = activeCycles(live);
        return list.stream().map(e -> {
            List<SessionAttendance> places = bySession.getOrDefault(e.getId(), List.of());
            List<AttendeeView> attendees = places.stream().map(a -> {
                StudentSummary student = byStudent.get(a.getStudentId());
                return new AttendeeView(a.getId(), a.getStudentId(), student.fullName(), a.getStatus(), a.isOverride(),
                        a.getOverrideReason(), a.isConfirmed(), a.getStudentConfirmationMethod(), a.getStudentConfirmedAt(),
                        ConfirmationRules.isOnlyMarkedByCoach(a.getStatus(), a.isConfirmed()),
                        attendanceRules.mayMark(a.getStatus(), e.getStartsAt(), cycleActive.get(a.getCycleId())), student.minor());
            }).toList();
            int occupied = places.size();
            boolean canShowQr = e.getStatus() == EventStatus.SCHEDULED
                    && confirmation.mayIssue(e.getStartsAt(), e.getEndsAt(), settings.qrOpenMinutesBefore(), settings.qrCloseHoursAfterEnd());
            return new EventView(e.getId(), e.getStartsAt(), e.getEndsAt(), e.getModality(), e.getCapacity(), occupied,
                    Math.max(0, e.getCapacity() - occupied), occupied > e.getCapacity(), e.getStatus(), eventRules.phase(e.getStartsAt(), e.getEndsAt()), canShowQr,
                    attendees);
        }).toList();
    }

    EventView eventView(ClassSession e) {
        return eventViews(List.of(e)).get(0);
    }

    /**
     * Attendance views. forCoach = true adds the student's id and name; the student's own views leave them null and only show
     * the event's modality, capacity and how many places are taken.
     */
    List<AttendanceView> attendanceViews(List<SessionAttendance> list, boolean forCoach) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<UUID> eventIds = list.stream().map(SessionAttendance::getSessionId).distinct().toList();
        Map<UUID, ClassSession> byId = events.findAllById(eventIds).stream().collect(Collectors.toMap(ClassSession::getId, Function.identity()));
        Map<UUID, Integer> counts = liveCounts(eventIds);
        Map<UUID, String> names = forCoach ? studentNames() : Map.of();
        int cancelWindowHours = coaches.schedulingSettings(TenantContext.get()).cancelWindowHours();
        Map<UUID, Boolean> cycleActive = activeCycles(list);
        return list.stream().map(a -> {
            ClassSession e = byId.get(a.getSessionId());
            return new AttendanceView(a.getId(), e.getId(), forCoach ? a.getStudentId() : null, names.get(a.getStudentId()),
                    a.getCycleId(), e.getStartsAt(), e.getEndsAt(), e.getModality(), e.getCapacity(),
                    e.getStatus() == EventStatus.CANCELLED ? 0 : counts.getOrDefault(e.getId(), 0), a.getStatus(),
                    a.getRescheduledFrom(), a.getCancelReason(), a.isOverride(), a.getOverrideReason(), a.isConfirmed(),
                    a.getStudentConfirmationMethod(), a.getStudentConfirmedAt(),
                    ConfirmationRules.isOnlyMarkedByCoach(a.getStatus(), a.isConfirmed()),
                    cancellation.mayStudentCancel(a.getStatus(), e.getStartsAt(), cancelWindowHours),
                    attendanceRules.mayMark(a.getStatus(), e.getStartsAt(), cycleActive.get(a.getCycleId())));
        }).toList();
    }

    AttendanceView attendanceView(SessionAttendance a, boolean forCoach) {
        return attendanceViews(List.of(a), forCoach).get(0);
    }
}
