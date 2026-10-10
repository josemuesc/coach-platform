package com.coachplatform.billing;

import com.coachplatform.billing.api.PlanInput;
import com.coachplatform.billing.api.PlanSummary;
import com.coachplatform.common.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanService {

    private final PlanRepository plans;
    private final BillingService billing;

    public PlanService(PlanRepository plans, BillingService billing) {
        this.plans = plans;
        this.billing = billing;
    }

    @Transactional
    public PlanSummary create(PlanInput input) {
        String name = input.name().trim();
        if (plans.existsByNameIgnoreCase(name)) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_NAME_EXISTS");
        }
        return toSummary(plans.save(new Plan(name, input.classesIncluded(), input.priceCop(), input.modality())), billing.activeStudentsByPlan());
    }

    @Transactional(readOnly = true)
    public List<PlanSummary> list() {
        Map<UUID, Integer> counts = billing.activeStudentsByPlan();
        return plans.findAllByOrderByClassesIncludedAsc().stream().map(p -> toSummary(p, counts)).toList();
    }

    @Transactional
    public PlanSummary update(UUID planId, PlanInput input) {
        Plan plan = plans.findById(planId).orElseThrow(PlanNotFoundException::new);
        String name = input.name().trim();
        if (plans.existsByNameIgnoreCaseAndIdNot(name, planId)) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_NAME_EXISTS");
        }
        plan.update(name, input.classesIncluded(), input.priceCop(), input.modality());
        return toSummary(plan, billing.activeStudentsByPlan());
    }

    @Transactional
    public PlanSummary setActive(UUID planId, boolean active) {
        Plan plan = plans.findById(planId).orElseThrow(PlanNotFoundException::new);
        plan.setActive(active);
        return toSummary(plan, billing.activeStudentsByPlan());
    }

    private static PlanSummary toSummary(Plan p, Map<UUID, Integer> activeStudents) {
        return new PlanSummary(p.getId(), p.getName(), p.getClassesIncluded(), p.getPriceCop(), p.isActive(), p.getModality(),
                activeStudents.getOrDefault(p.getId(), 0));
    }
}
