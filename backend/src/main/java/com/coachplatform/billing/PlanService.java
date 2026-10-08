package com.coachplatform.billing;

import com.coachplatform.billing.api.PlanInput;
import com.coachplatform.billing.api.PlanSummary;
import com.coachplatform.common.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanService {

    private final PlanRepository plans;

    public PlanService(PlanRepository plans) {
        this.plans = plans;
    }

    @Transactional
    public PlanSummary create(PlanInput input) {
        String name = input.name().trim();
        if (plans.existsByNameIgnoreCase(name)) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_NAME_EXISTS");
        }
        return toSummary(plans.save(new Plan(name, input.classesIncluded(), input.priceCop())));
    }

    @Transactional(readOnly = true)
    public List<PlanSummary> list() {
        return plans.findAllByOrderByClassesIncludedAsc().stream().map(PlanService::toSummary).toList();
    }

    @Transactional
    public PlanSummary update(UUID planId, PlanInput input) {
        Plan plan = plans.findById(planId).orElseThrow(PlanNotFoundException::new);
        String name = input.name().trim();
        if (plans.existsByNameIgnoreCaseAndIdNot(name, planId)) {
            throw new ApiException(HttpStatus.CONFLICT, "PLAN_NAME_EXISTS");
        }
        plan.update(name, input.classesIncluded(), input.priceCop());
        return toSummary(plan);
    }

    @Transactional
    public PlanSummary setActive(UUID planId, boolean active) {
        Plan plan = plans.findById(planId).orElseThrow(PlanNotFoundException::new);
        plan.setActive(active);
        return toSummary(plan);
    }

    private static PlanSummary toSummary(Plan p) {
        return new PlanSummary(p.getId(), p.getName(), p.getClassesIncluded(), p.getPriceCop(), p.isActive());
    }
}
