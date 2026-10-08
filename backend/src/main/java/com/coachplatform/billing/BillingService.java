package com.coachplatform.billing;

import com.coachplatform.billing.api.CycleStatus;
import com.coachplatform.billing.api.CycleSummary;
import com.coachplatform.billing.api.ExtendCycleCommand;
import com.coachplatform.billing.api.OverviewStatus;
import com.coachplatform.billing.api.PaymentRegistered;
import com.coachplatform.billing.api.PaymentSummary;
import com.coachplatform.billing.api.RegisterPaymentCommand;
import com.coachplatform.billing.api.StudentBillingOverview;
import com.coachplatform.billing.domain.CycleCalendar;
import com.coachplatform.billing.domain.CycleRuleException;
import com.coachplatform.billing.domain.CycleRules;
import com.coachplatform.billing.domain.CycleState;
import com.coachplatform.coach.CoachService;
import com.coachplatform.coach.api.BillingSettings;
import com.coachplatform.students.StudentService;
import com.coachplatform.students.api.StudentSummary;
import com.coachplatform.tenant.TenantContext;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API of billing. Orchestration only: it loads entities, translates them to {@link CycleState}, asks
 * {@link CycleRules} for the decision, and persists the result. Every write on a student's cycles first locks the
 * student row, so concurrent payments of the same student are serialized (the partial unique index is the last guard).
 */
@Service
public class BillingService {

    private final StudentService students;
    private final CoachService coaches;
    private final PlanRepository plans;
    private final CycleRepository cycles;
    private final PaymentRepository payments;
    private final CycleExtensionRepository extensions;
    private final CycleRules rules;
    private final CycleCalendar calendar;

    BillingService(StudentService students, CoachService coaches, PlanRepository plans, CycleRepository cycles, PaymentRepository payments,
                   CycleExtensionRepository extensions, CycleRules rules, CycleCalendar calendar) {
        this.students = students;
        this.coaches = coaches;
        this.plans = plans;
        this.cycles = cycles;
        this.payments = payments;
        this.extensions = extensions;
        this.rules = rules;
        this.calendar = calendar;
    }

    /** Records a payment and opens its cycle in ONE transaction. */
    @Transactional
    public PaymentRegistered registerPayment(UUID studentId, RegisterPaymentCommand cmd, UUID recordedBy) {
        students.lockForUpdate(studentId);
        Plan plan = plans.findById(cmd.planId()).filter(Plan::isActive).orElseThrow(PlanNotFoundException::new);

        Optional<Cycle> previous = cycles.findFirstByStudentIdOrderByStartDateDescCreatedAtDesc(studentId);
        var result = rules.openCycle(cmd.paidOn(), plan.getClassesIncluded(), previous.map(c -> c.toState(calendar)));

        try {
            if (result.previousUpdated().isPresent()) {
                previous.orElseThrow().apply(result.previousUpdated().get(), calendar.now());
                // Flush BEFORE inserting the new cycle: Hibernate would otherwise order the INSERT first and the
                // partial unique index would still see the old cycle as ACTIVE.
                cycles.saveAndFlush(previous.get());
            }
            Cycle cycle = cycles.saveAndFlush(new Cycle(studentId, plan.getId(), result.newCycle()));
            long amount = cmd.amountCop() != null ? cmd.amountCop() : plan.getPriceCop();
            Payment payment = payments.saveAndFlush(new Payment(studentId, cycle.getId(), amount, cmd.method(),
                    result.newCycle().startDate(), recordedBy));
            return new PaymentRegistered(payment.getId(), cycle.getId(), result.newCycle().startDate(),
                    result.newCycle().endDate());
        } catch (DataIntegrityViolationException e) {
            // Defence in depth: the unique index caught a race the lock should already have prevented.
            throw new CycleRuleException(CycleRuleException.Code.ACTIVE_CYCLE_EXISTS,
                    "The student already has an active cycle");
        }
    }

    @Transactional
    public CycleSummary extendCycle(UUID cycleId, ExtendCycleCommand cmd, UUID extendedBy) {
        Cycle cycle = cycles.findById(cycleId).orElseThrow(CycleNotFoundException::new);
        students.lockForUpdate(cycle.getStudentId());
        cycle = cycles.findById(cycleId).orElseThrow(CycleNotFoundException::new); // re-read under the lock

        BillingSettings settings = coaches.billingSettings(TenantContext.get());
        var result = rules.extend(cycle.toState(calendar), cmd.newEndDate(), extendedBy, settings.maxExtensionDays());
        cycle.apply(result.cycle(), calendar.now());
        cycles.saveAndFlush(cycle);
        var record = result.record();
        extensions.save(new CycleExtensionRecord(cycleId, record.previousEndDate(), record.newEndDate(),
                cmd.reason().trim(), record.extendedBy(), record.extendedAt()));
        return toSummary(cycle);
    }

    /** Persists the real status of a cycle that is overdue or fully used. Idempotent; used by the daily job. */
    @Transactional
    public void closeIfDue(UUID cycleId) {
        Cycle cycle = cycles.findById(cycleId).orElse(null);
        if (cycle == null) {
            return;
        }
        students.lockForUpdate(cycle.getStudentId());
        cycle = cycles.findById(cycleId).orElse(null);
        if (cycle == null) {
            return;
        }
        CycleState stored = cycle.toState(calendar);
        CycleState effective = rules.evaluate(stored);
        if (!effective.equals(stored)) {
            cycle.apply(effective, calendar.now());
            cycles.save(cycle);
        }
    }

    @Transactional(readOnly = true)
    public Optional<CycleSummary> activeCycle(UUID studentId) {
        students.get(studentId); // 404 for a student of another tenant
        return cycles.findFirstByStudentIdAndStatus(studentId, CycleStatus.ACTIVE)
                .map(this::toSummary)
                .filter(c -> c.status() == CycleStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public List<CycleSummary> cycles(UUID studentId) {
        students.get(studentId);
        return cycles.findByStudentIdOrderByStartDateDescCreatedAtDesc(studentId).stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentSummary> payments(UUID studentId) {
        students.get(studentId);
        return payments.findByStudentIdOrderByPaidOnDescCreatedAtDesc(studentId).stream()
                .map(p -> new PaymentSummary(p.getId(), p.getStudentId(), p.getCycleId(), p.getAmountCop(),
                        p.getMethod(), p.getPaidOn(), p.getRecordedBy(), p.getCreatedAt()))
                .toList();
    }

    /**
     * Active students with their cycle state: ACTIVE, EXPIRING_SOON (few days or few classes left, per the coach's
     * settings: 5 days / 1 class by default) or NO_CYCLE.
     */
    @Transactional(readOnly = true)
    public List<StudentBillingOverview> overview() {
        Map<UUID, CycleSummary> activeByStudent = new HashMap<>();
        for (Cycle cycle : cycles.findByStatus(CycleStatus.ACTIVE)) {
            CycleSummary summary = toSummary(cycle);
            if (summary.status() == CycleStatus.ACTIVE) {
                activeByStudent.put(summary.studentId(), summary);
            }
        }
        var today = calendar.today();
        BillingSettings settings = coaches.billingSettings(TenantContext.get());
        return students.list().stream().filter(StudentSummary::active).map(s -> {
            CycleSummary c = activeByStudent.get(s.id());
            if (c == null) {
                return new StudentBillingOverview(s.id(), s.fullName(), OverviewStatus.NO_CYCLE, null, null);
            }
            boolean soon = ChronoUnit.DAYS.between(today, c.endDate()) <= settings.expiringSoonDays()
                    || c.classesRemaining() <= settings.expiringSoonClasses();
            return new StudentBillingOverview(s.id(), s.fullName(),
                    soon ? OverviewStatus.EXPIRING_SOON : OverviewStatus.ACTIVE, c.endDate(), c.classesRemaining());
        }).toList();
    }

    private CycleSummary toSummary(Cycle cycle) {
        CycleState effective = rules.evaluate(cycle.toState(calendar));
        return new CycleSummary(cycle.getId(), cycle.getStudentId(), cycle.getPlanId(), effective.startDate(),
                effective.endDate(), cycle.getOriginalEndDate(), effective.classesIncluded(), effective.classesUsed(),
                effective.classesRemaining(), effective.classesLost(), effective.status());
    }
}
