package com.coachplatform.scheduling;

import com.coachplatform.billing.BillingService;
import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.api.EventStatus;
import com.coachplatform.scheduling.api.StudentProfile;
import com.coachplatform.scheduling.api.UpcomingClass;
import com.coachplatform.scheduling.domain.SlotCalendar;
import com.coachplatform.students.ConsentService;
import com.coachplatform.students.StudentAccountService;
import com.coachplatform.students.StudentService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read model: the coach's student screen. Composes the student, their login, their cycle, their next classes and their consents. */
@Service
public class StudentProfileService {

    private static final int UPCOMING_LIMIT = 5;

    private final StudentService students;
    private final StudentAccountService accounts;
    private final BillingService billing;
    private final ConsentService consents;
    private final ClassSessionRepository events;
    private final SessionAttendanceRepository attendances;
    private final SlotCalendar calendar;
    private final Clock clock;

    StudentProfileService(StudentService students, StudentAccountService accounts, BillingService billing, ConsentService consents,
                          ClassSessionRepository events, SessionAttendanceRepository attendances, SlotCalendar calendar, Clock clock) {
        this.students = students;
        this.accounts = accounts;
        this.billing = billing;
        this.consents = consents;
        this.events = events;
        this.attendances = attendances;
        this.calendar = calendar;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StudentProfile of(UUID studentId) {
        var student = students.get(studentId);                       // 404 for a student of another tenant
        return new StudentProfile(student, accounts.of(studentId), billing.cycleOverview(studentId), upcoming(studentId),
                consents.forStudent(studentId), null);   // emergency contact: step 5
    }

    private List<UpcomingClass> upcoming(UUID studentId) {
        List<SessionAttendance> booked = attendances.findByStudentIdOrderByCreatedAtDesc(studentId).stream()
                .filter(a -> a.getStatus() == AttendanceStatus.SCHEDULED).toList();
        if (booked.isEmpty()) {
            return List.of();
        }
        Map<UUID, ClassSession> byId = events.findAllById(booked.stream().map(SessionAttendance::getSessionId).distinct().toList()).stream()
                .collect(Collectors.toMap(ClassSession::getId, Function.identity()));
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, calendar.zone());
        return booked.stream()
                .map(a -> Map.entry(a, byId.get(a.getSessionId())))
                .filter(e -> e.getValue().getStatus() == EventStatus.SCHEDULED && e.getValue().getEndsAt().isAfter(now))
                .sorted(Comparator.comparing((Map.Entry<SessionAttendance, ClassSession> e) -> e.getValue().getStartsAt()))
                .limit(UPCOMING_LIMIT)
                .map(e -> new UpcomingClass(e.getKey().getId(), e.getValue().getId(), e.getValue().getStartsAt(), e.getValue().getEndsAt(),
                        e.getValue().getModality(), LocalDate.ofInstant(e.getValue().getStartsAt(), calendar.zone()).equals(today)))
                .toList();
    }
}
