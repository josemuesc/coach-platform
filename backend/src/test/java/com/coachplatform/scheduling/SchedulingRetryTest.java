package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coachplatform.billing.BillingService;
import com.coachplatform.billing.api.CycleStatus;
import com.coachplatform.billing.api.CycleSummary;
import com.coachplatform.billing.api.Modality;
import com.coachplatform.coach.CoachService;
import com.coachplatform.coach.api.SchedulingSettings;
import com.coachplatform.common.ApiException;
import com.coachplatform.common.ConcurrentChangeException;
import com.coachplatform.scheduling.api.AttendanceView;
import com.coachplatform.scheduling.domain.BookingRules;
import com.coachplatform.scheduling.domain.CancellationPolicy;
import com.coachplatform.scheduling.domain.EventRules;
import com.coachplatform.scheduling.domain.SchedulingRuleException;
import com.coachplatform.scheduling.domain.SlotCalendar;
import com.coachplatform.students.StudentService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The real SchedulingService with its collaborators mocked, to prove - deterministically - that a CONCURRENT_CHANGE makes the
 * server repeat the WHOLE operation in a NEW transaction (at most 3 attempts) before the 409 reaches the client.
 */
class SchedulingRetryTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final Instant NOW = Instant.parse("2026-10-06T17:00:00Z");
    private static final Instant WHEN = LocalDate.of(2026, 10, 13).atTime(LocalTime.of(10, 0)).atZone(BOGOTA).toInstant();

    private final UUID studentId = UUID.randomUUID();
    private final UUID coachUser = UUID.randomUUID();
    private final UUID busyEventId = UUID.randomUUID();

    private StudentService students;
    private BillingService billing;
    private CoachService coaches;
    private ClassSessionRepository events;
    private SessionAttendanceRepository attendances;
    private AvailabilityRuleRepository availability;
    private AvailabilityBlockRepository blocks;
    private SchedulingViews views;
    private TransactionTemplate tx;
    private SchedulingService service;
    private final AtomicInteger transactionsOpened = new AtomicInteger();

    @BeforeEach
    void setUp() {
        students = mock(StudentService.class);
        billing = mock(BillingService.class);
        coaches = mock(CoachService.class);
        events = mock(ClassSessionRepository.class);
        attendances = mock(SessionAttendanceRepository.class);
        availability = mock(AvailabilityRuleRepository.class);
        blocks = mock(AvailabilityBlockRepository.class);
        views = mock(SchedulingViews.class);
        tx = mock(TransactionTemplate.class);

        // every call to tx.execute is "a new transaction": count it and run the work
        doAnswer(inv -> {
            transactionsOpened.incrementAndGet();
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(tx).execute(any());

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SlotCalendar calendar = new SlotCalendar(BOGOTA);
        service = new SchedulingService(students, billing, coaches, events, attendances, availability, blocks, views,
                new BookingRules(clock, calendar), new CancellationPolicy(clock), mock(AttendanceMarker.class), mock(AttendanceAuditWriter.class),
                new EventRules(clock),
                calendar, clock, tx);

        when(coaches.schedulingSettings(any())).thenReturn(new SchedulingSettings(2, 60, 4, 72, 15, 2));
        when(billing.activeCycle(studentId)).thenReturn(Optional.of(new CycleSummary(UUID.randomUUID(), studentId, UUID.randomUUID(),
                LocalDate.of(2026, 10, 6), LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 6), 8, 0, 8, 0, 0,
                CycleStatus.ACTIVE, Modality.PERSONALIZED)));
        when(availability.findAllByOrderByDayOfWeekAscStartTimeAsc()).thenReturn(
                java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(d -> new AvailabilityRule(d, LocalTime.of(6, 0), LocalTime.of(20, 0))).toList());
        when(events.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(attendances.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(views.attendanceView(any(), anyBoolean())).thenReturn(mock(AttendanceView.class));
    }

    /** An event that was already cancelled by the time this request locks it: the read went stale. */
    private ClassSession cancelledEvent() {
        ClassSession event = new ClassSession(WHEN, WHEN.plusSeconds(3600), Modality.PERSONALIZED, 1, coachUser);
        event.cancel(coachUser, "x", NOW);
        return event;
    }

    @Test
    void aConflictOnTheFirstAttemptIsRetriedInANewTransactionAndTheSecondAttemptSucceeds() {
        // attempt 1: the read sees an event, but it is cancelled once locked -> CONCURRENT_CHANGE. Attempt 2: the slot is empty.
        when(events.findScheduledOverlappingIds(any(), any())).thenReturn(List.of(busyEventId), List.of());
        when(events.findByIdForUpdate(busyEventId)).thenReturn(Optional.of(cancelledEvent()));

        AttendanceView result = service.bookAsCoach(studentId, WHEN, coachUser, false, null);

        assertThat(result).isNotNull();
        assertThat(transactionsOpened).as("two separate transactions").hasValue(2);
        verify(events, times(1)).findByIdForUpdate(busyEventId);
        verify(coaches, times(1)).lockCalendar(any());      // the second attempt found nothing, so it took the calendar lock and created the event
        verify(events, times(1)).saveAndFlush(any());
    }

    @Test
    void ifTheConflictKeepsHappeningTheClientGetsThe409AfterExactlyThreeAttempts() {
        when(events.findScheduledOverlappingIds(any(), any())).thenReturn(List.of(busyEventId));
        when(events.findByIdForUpdate(busyEventId)).thenAnswer(inv -> Optional.of(cancelledEvent()));

        assertThatThrownBy(() -> service.bookAsCoach(studentId, WHEN, coachUser, false, null))
                .isInstanceOf(ConcurrentChangeException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("CONCURRENT_CHANGE"));

        assertThat(transactionsOpened).as("max 3 attempts, each in its own transaction").hasValue(3);
        verify(events, times(3)).findByIdForUpdate(busyEventId);
        verify(events, times(0)).saveAndFlush(any());        // nothing was ever written
    }

    @Test
    void aBusinessRejectionIsNeverRetried() {
        when(availability.findAllByOrderByDayOfWeekAscStartTimeAsc()).thenReturn(List.of());   // no availability: NOT_AVAILABLE
        when(events.findScheduledOverlappingIds(any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.bookAsCoach(studentId, WHEN, coachUser, false, null))
                .isInstanceOf(SchedulingRuleException.class);

        assertThat(transactionsOpened).as("a rule violation would fail again: one attempt only").hasValue(1);
    }
}
