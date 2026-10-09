package com.coachplatform.scheduling;

import com.coachplatform.scheduling.api.AttendanceView;
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

    SchedulingViews(ClassSessionRepository events, SessionAttendanceRepository attendances, StudentService students) {
        this.events = events;
        this.attendances = attendances;
        this.students = students;
    }

    Map<UUID, String> studentNames() {
        return students.list().stream().collect(Collectors.toMap(StudentSummary::id, StudentSummary::fullName));
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
        Map<UUID, String> names = studentNames();
        var live = attendances.findLiveBySessions(list.stream().map(ClassSession::getId).toList());
        Map<UUID, List<SessionAttendance>> bySession = live.stream().collect(Collectors.groupingBy(SessionAttendance::getSessionId));
        return list.stream().map(e -> {
            List<SessionAttendance> places = bySession.getOrDefault(e.getId(), List.of());
            List<AttendeeView> attendees = places.stream().map(a -> new AttendeeView(a.getId(), a.getStudentId(),
                    names.get(a.getStudentId()), a.getStatus(), a.isOverride(), a.getOverrideReason(), a.isConfirmed(),
                    a.getStudentConfirmationMethod(), a.getStudentConfirmedAt(),
                    ConfirmationRules.isOnlyMarkedByCoach(a.getStatus(), a.isConfirmed()))).toList();
            int occupied = places.size();
            return new EventView(e.getId(), e.getStartsAt(), e.getEndsAt(), e.getModality(), e.getCapacity(), occupied,
                    Math.max(0, e.getCapacity() - occupied), e.getStatus(), attendees);
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
        return list.stream().map(a -> {
            ClassSession e = byId.get(a.getSessionId());
            return new AttendanceView(a.getId(), e.getId(), forCoach ? a.getStudentId() : null, names.get(a.getStudentId()),
                    a.getCycleId(), e.getStartsAt(), e.getEndsAt(), e.getModality(), e.getCapacity(),
                    e.getStatus() == EventStatus.CANCELLED ? 0 : counts.getOrDefault(e.getId(), 0), a.getStatus(),
                    a.getRescheduledFrom(), a.getCancelReason(), a.isOverride(), a.getOverrideReason(), a.isConfirmed(),
                    a.getStudentConfirmationMethod(), a.getStudentConfirmedAt(),
                    ConfirmationRules.isOnlyMarkedByCoach(a.getStatus(), a.isConfirmed()));
        }).toList();
    }

    AttendanceView attendanceView(SessionAttendance a, boolean forCoach) {
        return attendanceViews(List.of(a), forCoach).get(0);
    }
}
