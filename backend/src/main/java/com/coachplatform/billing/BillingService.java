package com.coachplatform.billing;

import com.coachplatform.billing.api.CycleSessions;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API of billing. Orchestration only: it loads entities, translates them to {@link CycleState}, asks
 * {@link CycleRules} for the decision, and persists the result. Every write on a student's cycles first locks the
 * student row (always the FIRST lock taken), so concurrent work on one student is serialized; the partial unique
 * index is the last guard.
 */
@Service
public class BillingService {

    private final StudentService students;
    private final CoachService coaches;
    private final PlanRepository plans;
    private final CycleRepository cycles;
    private final PaymentRepository payments;
    private final CycleExtensionRepository extensions;
    private final CycleSessions cycleSessions;
    private final CycleRules rules;
    private final CycleCalendar calendar;

    BillingService(StudentService students, CoachService coaches, PlanRepository plans, CycleRepository cycles,
                   PaymentRepository payments, CycleExtensionRepository extensions, CycleSessions cycleSessions,
                   CycleRules rules, CycleCalendar calendar) {
        this.students = students;
        this.coaches = coaches;
        this.plans = plans;
        this.cycles = cycles;
        this.payments = payments;
        this.extensions = extensions;
        this.cycleSessions = cycleSessions;
        this.rules = rules;
        this.calendar = calendar;
    }

    /** Records a payment and opens its cycle in ONE transaction. */
    @Transactional
    public PaymentRegistered registerPayment(UUID studentId, RegisterPaymentCommand cmd, UUID recordedBy) {
        students.lockForUpdate(studentId);
        Plan plan = plans.findById(cmd.planId()).filter(Plan::isActive).orElseThrow(PlanNotFoundException::new);

        Optional<Cycle> previous = cycles.findFirstByStudentIdOrderByStartDateDescCreatedAtDesc(studentId);
        int pending = previous.map(c -> pendingMarks(c, c.toState(calendar))).orElse(0);
        var result = openCycle(cmd, plan, previous, pending);

        // Classes of the old cycle that have not started yet (only possible when renewing on its deadline day) move
        // to the new cycle and count against ITS quota: check before writing anything.
        int toTransfer = result.previousUpdated().isPresent()
                ? cycleSessions.futureScheduledCount(previous.orElseThrow().getId()) : 0;
        if (toTransfer > plan.getClassesIncluded()) {
            throw new CycleRuleException(CycleRuleException.Code.TRANSFER_EXCEEDS_PLAN, toTransfer
                    + " scheduled class(es) of the previous cycle do not fit in a plan of " + plan.getClassesIncluded());
        }

        try {
            if (result.previousUpdated().isPresent()) {
                previous.orElseThrow().apply(result.previousUpdated().get(), calendar.now());
                // Flush BEFORE inserting the new cycle: Hibernate would otherwise order the INSERT first and the
                // partial unique index would still see the old cycle as ACTIVE.
                cycles.saveAndFlush(previous.get());
            }
            Cycle cycle = cycles.saveAndFlush(new Cycle(studentId, plan.getId(), result.newCycle()));
            if (toTransfer > 0) {
                cycleSessions.moveFutureSessions(previous.orElseThrow().getId(), cycle.getId());
            }
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

    private CycleRules.OpenCycleResult openCycle(RegisterPaymentCommand cmd, Plan plan, Optional<Cycle> previous, int pending) {
        try {
            return rules.openCycle(cmd.paidOn(), plan.getClassesIncluded(), previous.map(c -> c.toState(calendar)), pending);
        } catch (CycleRuleException e) {
            if (e.code() == CycleRuleException.Code.PENDING_SESSIONS_TO_MARK) {
                throw new PendingSessionsException(cycleSessions.pendingMarks(previous.orElseThrow().getId()));
            }
            throw e;
        }
    }

    /**
     * Moves the deadline of a cycle, or REOPENS an expired one (only the student's latest cycle, never a completed
     * one), within the cap over the original deadline. The reason is mandatory and the audit row records who, when,
     * the previous and new deadline, and whether it was a reopening.
     */
    @Transactional
    public CycleSummary extendCycle(UUID cycleId, ExtendCycleCommand cmd, UUID extendedBy) {
        UUID studentId = cycles.findStudentIdById(cycleId).orElseThrow(CycleNotFoundException::new);
        students.lockForUpdate(studentId);                                          // 1st lock: the student
        Cycle cycle = cycles.findById(cycleId).orElseThrow(CycleNotFoundException::new);   // read under the lock

        boolean hasNewer = cycles.findFirstByStudentIdOrderByStartDateDescCreatedAtDesc(cycle.getStudentId())
                .map(latest -> !latest.getId().equals(cycleId)).orElse(false);
        CycleState stored = cycle.toState(calendar);
        BillingSettings settings = coaches.billingSettings(TenantContext.get());
        var result = rules.extend(stored, cmd.newEndDate(), extendedBy, settings.maxExtensionDays(),
                pendingMarks(cycle, stored), hasNewer);
        cycle.apply(result.cycle(), calendar.now());
        cycles.saveAndFlush(cycle);
        var record = result.record();
        extensions.save(new CycleExtensionRecord(cycleId, record.previousEndDate(), record.newEndDate(),
                cmd.reason().trim(), record.extendedBy(), record.extendedAt(), record.reopened()));
        return toSummary(cycle);
    }

    /**
     * Uses up one class of the cycle (the class was just marked attended / no-show). The caller holds the student
     * lock and marks the class AFTER this call, so the class being marked still counts as pending: a cycle past its
     * deadline stays open exactly long enough to take it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CycleSummary consumeClass(UUID cycleId) {
        Cycle cycle = cycles.findById(cycleId).orElseThrow(CycleNotFoundException::new);
        CycleState stored = cycle.toState(calendar);
        cycle.apply(rules.consumeClass(stored, pendingMarks(cycle, stored)), calendar.now());
        cycles.saveAndFlush(cycle);
        return toSummary(cycle);
    }

    /** Persists the real status of a cycle that is overdue or fully used. Idempotent; used by the daily job. */
    @Transactional
    public void closeIfDue(UUID cycleId) {
        UUID studentId = cycles.findStudentIdById(cycleId).orElse(null);
        if (studentId == null) {
            return;
        }
        students.lockForUpdate(studentId);                                          // 1st lock: the student
        Cycle cycle = cycles.findById(cycleId).orElse(null);                        // read under the lock
        if (cycle == null) {
            return;
        }
        CycleState stored = cycle.toState(calendar);
        CycleState effective = rules.evaluate(stored, pendingMarks(cycle, stored));
        if (!effective.equals(stored)) {
            cycle.apply(effective, calendar.now());
            cycles.save(cycle);
        }
    }

    /** One cycle with its effective state (404 for a cycle of another tenant). */
    @Transactional(readOnly = true)
    public CycleSummary cycle(UUID cycleId) {
        return toSummary(cycles.findById(cycleId).orElseThrow(CycleNotFoundException::new));
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
     * settings: 5 days / 1 class by default) or NO_CYCLE, plus how many started classes still need marking.
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
                return new StudentBillingOverview(s.id(), s.fullName(), OverviewStatus.NO_CYCLE, null, null, 0);
            }
            boolean soon = ChronoUnit.DAYS.between(today, c.endDate()) <= settings.expiringSoonDays()
                    || c.classesRemaining() <= settings.expiringSoonClasses();
            return new StudentBillingOverview(s.id(), s.fullName(),
                    soon ? OverviewStatus.EXPIRING_SOON : OverviewStatus.ACTIVE, c.endDate(), c.classesRemaining(),
                    c.pendingMarks());
        }).toList();
    }

    /** Only an ACTIVE-as-stored cycle can have unmarked classes; closed cycles skip the query. */
    private int pendingMarks(Cycle cycle, CycleState stored) {
        return stored.isActive() ? cycleSessions.pendingMarkCount(cycle.getId()) : 0;
    }

    private CycleSummary toSummary(Cycle cycle) {
        CycleState stored = cycle.toState(calendar);
        int pending = pendingMarks(cycle, stored);
        CycleState effective = rules.evaluate(stored, pending);
        return new CycleSummary(cycle.getId(), cycle.getStudentId(), cycle.getPlanId(), effective.startDate(),
                effective.endDate(), cycle.getOriginalEndDate(), effective.classesIncluded(), effective.classesUsed(),
                effective.classesRemaining(), effective.classesLost(), effective.isActive() ? pending : 0,
                effective.status());
    }
}
