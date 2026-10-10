package com.coachplatform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.coachplatform.scheduling.api.BlockInput;
import com.coachplatform.scheduling.api.MarkItem;
import com.coachplatform.scheduling.api.WindowInput;
import com.coachplatform.students.StudentNotFoundException;
import com.coachplatform.students.StudentService;
import com.coachplatform.students.api.StudentInput;
import com.coachplatform.support.PostgresIntegrationTest;
import com.coachplatform.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Scheduling resources of two coaches on real PostgreSQL: availability, blocks, events and attendances never leak across tenants. */
class SchedulingIsolationPostgresIT extends PostgresIntegrationTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    @Autowired AuthService auth;
    @Autowired AppUserRepository users;
    @Autowired StudentService students;
    @Autowired PlanService plans;
    @Autowired BillingService billing;
    @Autowired AvailabilityService availability;
    @Autowired BlockService blockService;
    @Autowired SchedulingService scheduling;
    @Autowired JdbcTemplate jdbc;

    private UUID coach(String prefix) {
        return auth.registerCoach(new RegisterCoachRequest(prefix, prefix + "-" + UUID.randomUUID() + "@test.co", "Prueba-1234-x")).coachId();
    }

    private UUID userOf(UUID coachId) {
        return users.findAll().stream().filter(u -> u.getCoachId().equals(coachId)).findFirst().orElseThrow().getId();
    }

    @Test
    void coachBCannotReachAvailabilityBlocksEventsOrAttendancesOfCoachA() {
        UUID a = coach("a");
        UUID b = coach("b");
        UUID userA = userOf(a);
        UUID userB = userOf(b);
        LocalDate day = LocalDate.now(BOGOTA).plusDays(3);
        Instant tenAm = day.atTime(LocalTime.of(10, 0)).atZone(BOGOTA).toInstant();

        UUID studentA = TenantContext.callAs(a, () -> {
            availability.replaceWeekly(List.of(1, 2, 3, 4, 5, 6, 7).stream().map(d -> new WindowInput(d, "06:00", "20:00")).toList());
            UUID plan = plans.create(new PlanInput("grupal", 8, 1L, Modality.SEMI_PERSONALIZED)).id();
            UUID student = students.create(new StudentInput("Alumno A", "sa-" + UUID.randomUUID() + "@test.co", null, java.time.LocalDate.of(1990, 5, 1), null, null), userA).student().id();
            billing.registerPayment(student, new RegisterPaymentCommand(plan, 520_000L, PaymentMethod.CASH, null), userA);
            return student;
        });
        AttendanceView placeA = TenantContext.callAs(a, () -> scheduling.bookAsCoach(studentA, tenAm, userA, false, null));
        TenantContext.runAs(a, () -> blockService.create(new BlockInput(tenAm.atZone(java.time.ZoneId.of("America/Bogota")).toLocalDate().plusDays(1), true, null, null, "festivo", java.util.List.of()), userA));

        TenantContext.runAs(b, () -> {
            assertThat(availability.weekly()).isEmpty();
            assertThat(availability.blocks(tenAm.minus(10, ChronoUnit.DAYS), tenAm.plus(10, ChronoUnit.DAYS))).isEmpty();
            assertThat(scheduling.agenda(day, day).events()).isEmpty();
            assertThat(scheduling.pending()).isEmpty();
            assertThatThrownBy(() -> scheduling.attendancesOfStudent(studentA)).isInstanceOf(StudentNotFoundException.class);
            assertThatThrownBy(() -> scheduling.bookAsCoach(studentA, tenAm.plus(1, ChronoUnit.HOURS), userB, false, null)).isInstanceOf(StudentNotFoundException.class);
            assertThatThrownBy(() -> scheduling.cancelAsCoach(placeA.id(), "intruso", null, false, null, userB)).isInstanceOf(AttendanceNotFoundException.class);
            assertThatThrownBy(() -> scheduling.markAttendance(placeA.id(), AttendanceStatus.ATTENDED, userB)).isInstanceOf(AttendanceNotFoundException.class);
            assertThatThrownBy(() -> scheduling.cancelEvent(placeA.eventId(), "intruso", userB)).isInstanceOf(EventNotFoundException.class);
            assertThatThrownBy(() -> scheduling.changeCapacity(placeA.eventId(), 3)).isInstanceOf(EventNotFoundException.class);
            assertThatThrownBy(() -> scheduling.markEvent(placeA.eventId(), List.of(new MarkItem(placeA.id(), AttendanceStatus.ATTENDED)), userB))
                    .isInstanceOf(EventNotFoundException.class);
        });

        TenantContext.runAs(a, () -> {
            assertThat(availability.weekly()).hasSize(7);
            assertThat(scheduling.agenda(day, day).events()).extracting(e -> e.id()).containsExactly(placeA.eventId());
            assertThat(scheduling.attendancesOfStudent(studentA)).extracting(s -> s.status()).containsExactly(AttendanceStatus.SCHEDULED);
        });
    }

    @Test
    void weeklyWindowsAreStoredAsTheWallClockTimesTheCoachGave() {
        UUID a = coach("wall");
        TenantContext.runAs(a, () -> availability.replaceWeekly(List.of(new WindowInput(1, "06:00", "09:30"), new WindowInput(3, "17:00", "20:00"))));

        // what is really in the database, read as text so no client-side conversion can hide a shift
        var rows = jdbc.queryForList("select day_of_week, start_time::text s, end_time::text e from availability_rule "
                + "where coach_id = ? order by day_of_week", a);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("s")).isEqualTo("06:00:00");
        assertThat(rows.get(0).get("e")).isEqualTo("09:30:00");
        assertThat(rows.get(1).get("s")).isEqualTo("17:00:00");
        assertThat(rows.get(1).get("e")).isEqualTo("20:00:00");
        TenantContext.runAs(a, () -> assertThat(availability.weekly()).extracting(w -> w.start() + "-" + w.end())
                .containsExactly("06:00-09:30", "17:00-20:00"));
    }
}
