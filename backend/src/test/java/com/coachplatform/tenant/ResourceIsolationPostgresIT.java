package com.coachplatform.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coachplatform.auth.AppUserRepository;
import com.coachplatform.auth.AuthDtos.RegisterCoachRequest;
import com.coachplatform.auth.AuthService;
import com.coachplatform.billing.BillingService;
import com.coachplatform.billing.CycleNotFoundException;
import com.coachplatform.billing.PlanNotFoundException;
import com.coachplatform.billing.PlanService;
import com.coachplatform.billing.api.ExtendCycleCommand;
import com.coachplatform.billing.api.PaymentMethod;
import com.coachplatform.billing.api.PaymentRegistered;
import com.coachplatform.billing.api.PlanInput;
import com.coachplatform.billing.api.RegisterPaymentCommand;
import com.coachplatform.students.StudentNotFoundException;
import com.coachplatform.students.StudentService;
import com.coachplatform.students.api.StudentInput;
import com.coachplatform.support.PostgresIntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Two coaches, every new resource, real PostgreSQL: coach B cannot see, read, use or change anything of coach A. */
class ResourceIsolationPostgresIT extends PostgresIntegrationTest {

    @Autowired AuthService auth;
    @Autowired AppUserRepository users;
    @Autowired StudentService students;
    @Autowired PlanService plans;
    @Autowired BillingService billing;

    private UUID newCoach(String prefix) {
        return auth.registerCoach(new RegisterCoachRequest(prefix, prefix + "-" + UUID.randomUUID() + "@test.co", "Prueba-1234-x")).coachId();
    }

    private UUID userOf(UUID coachId) {
        return users.findAll().stream().filter(u -> u.getCoachId().equals(coachId)).findFirst().orElseThrow().getId();
    }

    @Test
    void coachBCannotReachAnythingOfCoachA() {
        UUID coachA = newCoach("a");
        UUID coachB = newCoach("b");
        UUID userA = userOf(coachA);
        UUID userB = userOf(coachB);

        UUID planA = TenantContext.callAs(coachA, () -> plans.create(new PlanInput("8 clases", 8, 520_000L, com.coachplatform.billing.api.Modality.PERSONALIZED)).id());
        UUID studentA = TenantContext.callAs(coachA,
                () -> students.create(new StudentInput("Alumno A", "a-" + UUID.randomUUID() + "@test.co", null), userA).student().id());
        PaymentRegistered paid = TenantContext.callAs(coachA, () -> billing.registerPayment(studentA,
                new RegisterPaymentCommand(planA, 520_000L, PaymentMethod.CASH, null), userA));

        UUID planB = TenantContext.callAs(coachB, () -> plans.create(new PlanInput("8 clases", 8, 520_000L, com.coachplatform.billing.api.Modality.PERSONALIZED)).id());
        UUID studentB = TenantContext.callAs(coachB,
                () -> students.create(new StudentInput("Alumno B", "b-" + UUID.randomUUID() + "@test.co", null), userB).student().id());

        TenantContext.runAs(coachB, () -> {
            // plans
            assertThat(plans.list()).extracting(p -> p.id()).containsExactly(planB);
            assertThatThrownBy(() -> plans.update(planA, new PlanInput("hack", 1, 1L, com.coachplatform.billing.api.Modality.PERSONALIZED))).isInstanceOf(PlanNotFoundException.class);
            assertThatThrownBy(() -> plans.setActive(planA, false)).isInstanceOf(PlanNotFoundException.class);
            // students
            assertThat(students.list()).extracting(s -> s.id()).containsExactly(studentB);
            assertThatThrownBy(() -> students.get(studentA)).isInstanceOf(StudentNotFoundException.class);
            assertThatThrownBy(() -> students.reissueInvitation(studentA, userB)).isInstanceOf(StudentNotFoundException.class);
            // payments and cycles
            assertThatThrownBy(() -> billing.payments(studentA)).isInstanceOf(StudentNotFoundException.class);
            assertThatThrownBy(() -> billing.cycles(studentA)).isInstanceOf(StudentNotFoundException.class);
            assertThatThrownBy(() -> billing.activeCycle(studentA)).isInstanceOf(StudentNotFoundException.class);
            assertThatThrownBy(() -> billing.registerPayment(studentA, new RegisterPaymentCommand(planB, 520_000L, PaymentMethod.CASH, null), userB))
                    .isInstanceOf(StudentNotFoundException.class);
            assertThatThrownBy(() -> billing.registerPayment(studentB, new RegisterPaymentCommand(planA, 520_000L, PaymentMethod.CASH, null), userB))
                    .isInstanceOf(PlanNotFoundException.class);
            assertThatThrownBy(() -> billing.extendCycle(paid.cycleId(),
                    new ExtendCycleCommand(LocalDate.now().plusMonths(3), "intruso"), userB)).isInstanceOf(CycleNotFoundException.class);
            assertThat(billing.overview()).extracting(o -> o.studentId()).containsExactly(studentB);
        });

        TenantContext.runAs(coachA, () -> {
            assertThat(students.get(studentA).fullName()).isEqualTo("Alumno A");
            assertThat(billing.payments(studentA)).hasSize(1);
            assertThat(billing.activeCycle(studentA)).isPresent();
        });
    }
}
