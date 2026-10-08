package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.coachplatform.auth.AppUserRepository;
import com.coachplatform.auth.AuthDtos.RegisterCoachRequest;
import com.coachplatform.auth.AuthService;
import com.coachplatform.billing.BillingService;
import com.coachplatform.billing.PlanService;
import com.coachplatform.billing.api.Modality;
import com.coachplatform.billing.api.PaymentMethod;
import com.coachplatform.billing.api.PlanInput;
import com.coachplatform.billing.api.RegisterPaymentCommand;
import com.coachplatform.scheduling.api.AttendanceStatus;
import com.coachplatform.scheduling.api.AttendanceView;
import com.coachplatform.scheduling.api.CancelResult;
import com.coachplatform.scheduling.api.WindowInput;
import com.coachplatform.scheduling.domain.SchedulingRuleException;
import com.coachplatform.scheduling.domain.SchedulingRuleException.Code;
import com.coachplatform.students.StudentService;
import com.coachplatform.students.api.StudentInput;
import com.coachplatform.support.PostgresIntegrationTest;
import com.coachplatform.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Events and attendances under real concurrency against real PostgreSQL: calendar lock, event-row lock, student locks and the
 * no-overlap exclusion constraint working together. Every loser must get a CLEAN domain rejection - never a deadlock.
 */
class SchedulingConcurrencyIT extends PostgresIntegrationTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    @Autowired AuthService auth;
    @Autowired AppUserRepository users;
    @Autowired StudentService students;
    @Autowired PlanService plans;
    @Autowired BillingService billing;
    @Autowired AvailabilityService availability;
    @Autowired SchedulingService scheduling;
    @Autowired JdbcTemplate jdbc;

    record Coach(UUID coachId, UUID userId, UUID personalizedPlan, UUID semiPlan) {
    }

    private Coach coach(int planClasses) {
        String email = "coach-" + UUID.randomUUID() + "@test.co";
        UUID coachId = auth.registerCoach(new RegisterCoachRequest("Coach", email, "Prueba-1234-x")).coachId();
        UUID userId = users.findByEmailIgnoreCase(email).orElseThrow().getId();
        return TenantContext.callAs(coachId, () -> {
            List<WindowInput> week = new ArrayList<>();
            for (int d = 1; d <= 7; d++) {
                week.add(new WindowInput(d, "06:00", "20:00"));
            }
            availability.replaceWeekly(week);
            return new Coach(coachId, userId,
                    plans.create(new PlanInput("pers " + planClasses, planClasses, 520_000L, Modality.PERSONALIZED)).id(),
                    plans.create(new PlanInput("semi " + planClasses, planClasses, 320_000L, Modality.SEMI_PERSONALIZED)).id());
        });
    }

    private UUID student(Coach c, boolean semi) {
        return TenantContext.callAs(c.coachId(), () -> {
            UUID id = students.create(new StudentInput("Alumno", "a-" + UUID.randomUUID() + "@test.co", null), c.userId()).student().id();
            billing.registerPayment(id, new RegisterPaymentCommand(semi ? c.semiPlan() : c.personalizedPlan(), 520_000L, PaymentMethod.CASH, null), c.userId());
            return id;
        });
    }

    /** A bookable instant: N days ahead (Bogota) at the given hour. */
    private static Instant slot(int daysAhead, int hour) {
        return LocalDate.now(BOGOTA).plusDays(daysAhead).atTime(LocalTime.of(hour, 0)).atZone(BOGOTA).toInstant();
    }

    private Object attempt(Coach c, Callable<Object> action) {
        try {
            return TenantContext.callAs(c.coachId(), () -> {
                try {
                    return action.call();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (Throwable t) {
            return t;
        }
    }

    private Object book(Coach c, UUID studentId, Instant when) {
        return attempt(c, () -> scheduling.bookAsCoach(studentId, when, c.userId(), false, null));
    }

    private List<Object> race(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await();
        go.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    private static void assertCleanRejection(Object result, Code expected) {
        assertThat(result).as("a clean domain rejection, not a deadlock / constraint error").isInstanceOf(SchedulingRuleException.class);
        assertThat(((SchedulingRuleException) result).code()).isEqualTo(expected);
    }

    private static long winners(List<Object> results) {
        return results.stream().filter(r -> r instanceof AttendanceView).count();
    }

    private int events(UUID coachId) {
        return jdbc.queryForObject("select count(*) from class_session where coach_id = ? and status = 'SCHEDULED'", Integer.class, coachId);
    }

    // ================================================================= the two races the pilot coach asked for

    @Test
    void aPersonalizedAndASemiPersonalizedStudentTakingTheSameEmptyBlockAtOnceOnlyOneWins() throws Exception {
        for (int round = 0; round < 6; round++) {
            Coach c = coach(8);
            UUID personalized = student(c, false);
            UUID semi = student(c, true);
            Instant when = slot(3 + round, 10);

            List<Object> results = race(List.of(() -> book(c, personalized, when), () -> book(c, semi, when)));

            assertThat(winners(results)).as("exactly one takes the empty block").isEqualTo(1);
            results.stream().filter(r -> !(r instanceof AttendanceView)).forEach(r -> assertCleanRejection(r, Code.MODALITY_MISMATCH));
            assertThat(events(c.coachId())).as("a single event was created").isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from session_attendance where coach_id = ? and status = 'SCHEDULED'",
                    Integer.class, c.coachId())).isEqualTo(1);
        }
    }

    @Test
    void twoSemiPersonalizedStudentsRacingForTheLastSeatOnlyOneGetsIt() throws Exception {
        for (int round = 0; round < 6; round++) {
            Coach c = coach(8);
            UUID first = student(c, true);
            UUID second = student(c, true);
            UUID third = student(c, true);
            Instant when = slot(3 + round, 10);
            AttendanceView created = (AttendanceView) book(c, first, when);
            TenantContext.runAs(c.coachId(), () -> scheduling.changeCapacity(created.eventId(), 2));   // capacity 2, one seat left

            List<Object> results = race(List.of(() -> book(c, second, when), () -> book(c, third, when)));

            assertThat(winners(results)).as("exactly one takes the last seat").isEqualTo(1);
            results.stream().filter(r -> !(r instanceof AttendanceView)).forEach(r -> assertCleanRejection(r, Code.EVENT_FULL));
            assertThat(jdbc.queryForObject("select count(*) from session_attendance where session_id = ? and status = 'SCHEDULED'",
                    Integer.class, created.eventId())).isEqualTo(2);
        }
    }

    @Test
    void manySemiPersonalizedStudentsAtOnceNeverExceedTheCapacity() throws Exception {
        Coach c = coach(8);
        List<UUID> group = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            group.add(student(c, true));
        }
        Instant when = slot(4, 11);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (UUID s : group) {
            tasks.add(() -> book(c, s, when));
        }

        List<Object> results = race(tasks);

        assertThat(winners(results)).as("the default capacity is 4").isEqualTo(4);
        results.stream().filter(r -> !(r instanceof AttendanceView)).forEach(r -> assertCleanRejection(r, Code.EVENT_FULL));
        assertThat(events(c.coachId())).isEqualTo(1);
    }

    @Test
    void manyPersonalizedStudentsOnTheSameBlockLeaveASingleOne() throws Exception {
        Coach c = coach(8);
        List<UUID> group = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            group.add(student(c, false));
        }
        Instant when = slot(4, 12);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (UUID s : group) {
            tasks.add(() -> book(c, s, when));
        }

        List<Object> results = race(tasks);

        assertThat(winners(results)).isEqualTo(1);
        results.stream().filter(r -> !(r instanceof AttendanceView)).forEach(r -> assertCleanRejection(r, Code.SLOT_TAKEN));
    }

    // ================================================================= other invariants

    @Test
    void oneStudentBookingManySlotsAtOnceNeverExceedsTheCycleQuota() throws Exception {
        Coach c = coach(2);
        UUID ana = student(c, false);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int hour = 8; hour <= 13; hour++) {
            Instant when = slot(4, hour);
            tasks.add(() -> book(c, ana, when));
        }

        List<Object> results = race(tasks);

        assertThat(winners(results)).as("accepted").isEqualTo(2);
        results.stream().filter(r -> !(r instanceof AttendanceView)).forEach(r -> assertCleanRejection(r, Code.QUOTA_EXCEEDED));
    }

    @Test
    void joiningAndChangingTheCapacityAtTheSameTimeNeverLeavesMorePeopleThanTheCapacity() throws Exception {
        for (int round = 0; round < 6; round++) {
            Coach c = coach(8);
            UUID first = student(c, true);
            UUID second = student(c, true);
            UUID third = student(c, true);
            Instant when = slot(3 + round, 10);
            AttendanceView created = (AttendanceView) book(c, first, when);
            book(c, second, when);                                                                   // 2 inside, capacity 4
            TenantContext.runAs(c.coachId(), () -> scheduling.changeCapacity(created.eventId(), 3)); // one seat left

            List<Object> results = race(List.of(
                    () -> book(c, third, when),                                                      // takes the third seat...
                    () -> attempt(c, () -> scheduling.changeCapacity(created.eventId(), 2))));       // ...or the capacity drops to 2

            int capacity = jdbc.queryForObject("select capacity from class_session where id = ?", Integer.class, created.eventId());
            int occupied = jdbc.queryForObject("select count(*) from session_attendance where session_id = ? and status = 'SCHEDULED'",
                    Integer.class, created.eventId());
            assertThat(occupied).as("never over capacity (round " + round + ")").isLessThanOrEqualTo(capacity);
            results.stream().filter(r -> r instanceof Throwable).forEach(r -> assertThat(r).isInstanceOf(SchedulingRuleException.class));
        }
    }

    @Test
    void twoStudentsSwappingEventsAtTheSameTimeDoNotDeadlock() throws Exception {
        for (int round = 0; round < 6; round++) {
            Coach c = coach(8);
            UUID ana = student(c, true);
            UUID beto = student(c, true);
            Instant first = slot(3 + round, 10);
            Instant second = slot(3 + round, 14);
            AttendanceView anaPlace = (AttendanceView) book(c, ana, first);
            AttendanceView betoPlace = (AttendanceView) book(c, beto, second);

            // Ana moves to Beto's event while Beto moves to Ana's: opposite lock orders would deadlock without the sorted locks
            List<Object> results = race(List.of(
                    () -> attempt(c, () -> scheduling.cancelAsCoach(anaPlace.id(), "mover", second, false, null, c.userId())),
                    () -> attempt(c, () -> scheduling.cancelAsCoach(betoPlace.id(), "mover", first, false, null, c.userId()))));

            for (Object r : results) {
                if (r instanceof Throwable t) {
                    assertThat(t).as("only clean rejections, never a deadlock").isInstanceOfAny(SchedulingRuleException.class,
                            com.coachplatform.common.ApiException.class);
                }
            }
            assertThat(jdbc.queryForObject("select count(*) from session_attendance where coach_id = ? and status = 'SCHEDULED'",
                    Integer.class, c.coachId())).isEqualTo(2);                                       // nobody lost their place
        }
    }

    @Test
    void markingAndCancellingTheSamePlaceAtOnceOnlyOneWins() throws Exception {
        for (int round = 0; round < 5; round++) {
            Coach c = coach(8);
            UUID ana = student(c, false);
            UUID cycleId = jdbc.queryForObject("select id from cycle where student_id = ?", UUID.class, ana);
            // an event that already started with Ana still unmarked (it cannot be booked through the API: it is in the past)
            UUID event = jdbc.queryForObject("insert into class_session (coach_id, starts_at, ends_at, modality, capacity, status, created_by) "
                    + "values (?, now() - interval '2 hours', now() - interval '1 hour', 'PERSONALIZED', 1, 'SCHEDULED', ?) returning id",
                    UUID.class, c.coachId(), c.userId());
            UUID place = jdbc.queryForObject("insert into session_attendance (coach_id, session_id, student_id, cycle_id, status, created_by) "
                    + "values (?, ?, ?, ?, 'SCHEDULED', ?) returning id", UUID.class, c.coachId(), event, ana, cycleId, c.userId());

            List<Object> results = race(List.of(
                    () -> attempt(c, () -> scheduling.markAttendance(place, AttendanceStatus.ATTENDED, c.userId())),
                    () -> attempt(c, () -> scheduling.cancelAsCoach(place, "Perdonada", null, false, null, c.userId()))));

            assertThat(results.stream().filter(r -> !(r instanceof Throwable)).count()).as("exactly one of mark / cancel").isEqualTo(1);
            results.stream().filter(r -> r instanceof Throwable).forEach(r -> assertCleanRejection(r, Code.INVALID_STATE));
            String status = jdbc.queryForObject("select status from session_attendance where id = ?", String.class, place);
            int used = jdbc.queryForObject("select classes_used from cycle where id = ?", Integer.class, cycleId);
            assertThat(status.equals("ATTENDED") ? used == 1 : used == 0 && status.equals("CANCELLED_BY_COACH")).isTrue();
        }
    }

    @Test
    void cancellingTheWholeEventWhileSomeoneJoinsNeverLeavesALiveSeatInACancelledEvent() throws Exception {
        for (int round = 0; round < 6; round++) {
            Coach c = coach(8);
            UUID ana = student(c, true);
            UUID beto = student(c, true);
            Instant when = slot(3 + round, 10);
            AttendanceView created = (AttendanceView) book(c, ana, when);

            race(List.of(
                    () -> book(c, beto, when),
                    () -> attempt(c, () -> scheduling.cancelEvent(created.eventId(), "Entrenador enfermo", c.userId()))));

            Integer live = jdbc.queryForObject("select count(*) from session_attendance a join class_session s on s.id = a.session_id "
                    + "where s.id = ? and s.status = 'CANCELLED' and a.status in ('SCHEDULED','ATTENDED','NO_SHOW')", Integer.class, created.eventId());
            assertThat(live).as("no live place in a cancelled event (round " + round + ")").isZero();
        }
    }

    @Test
    void theLastClassMarkedByTwoRequestsCompletesTheCycleOnce() throws Exception {
        Coach c = coach(1);
        UUID ana = student(c, false);
        UUID cycleId = jdbc.queryForObject("select id from cycle where student_id = ?", UUID.class, ana);
        UUID event = jdbc.queryForObject("insert into class_session (coach_id, starts_at, ends_at, modality, capacity, status, created_by) "
                + "values (?, now() - interval '2 hours', now() - interval '1 hour', 'PERSONALIZED', 1, 'SCHEDULED', ?) returning id",
                UUID.class, c.coachId(), c.userId());
        UUID place = jdbc.queryForObject("insert into session_attendance (coach_id, session_id, student_id, cycle_id, status, created_by) "
                + "values (?, ?, ?, ?, 'SCHEDULED', ?) returning id", UUID.class, c.coachId(), event, ana, cycleId, c.userId());

        List<Object> results = race(List.of(
                () -> attempt(c, () -> scheduling.markAttendance(place, AttendanceStatus.ATTENDED, c.userId())),
                () -> attempt(c, () -> scheduling.markAttendance(place, AttendanceStatus.ATTENDED, c.userId()))));

        assertThat(results.stream().filter(r -> r instanceof AttendanceView)).hasSize(1);
        assertThat(jdbc.queryForObject("select classes_used from cycle where id = ?", Integer.class, cycleId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from cycle where id = ?", String.class, cycleId)).isEqualTo("COMPLETED");
        assertThat(results.stream().filter(r -> r instanceof CancelResult)).isEmpty();
    }
}
