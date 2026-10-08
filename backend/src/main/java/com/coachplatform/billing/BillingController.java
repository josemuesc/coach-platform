package com.coachplatform.billing;

import com.coachplatform.billing.api.CycleSummary;
import com.coachplatform.billing.api.ExtendCycleCommand;
import com.coachplatform.billing.api.PaymentRegistered;
import com.coachplatform.billing.api.PaymentSummary;
import com.coachplatform.billing.api.PlanInput;
import com.coachplatform.billing.api.PlanSummary;
import com.coachplatform.billing.api.RegisterPaymentCommand;
import com.coachplatform.billing.api.StudentBillingOverview;
import com.coachplatform.security.AuthPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/coach")
class BillingController {

    private final PlanService plans;
    private final BillingService billing;

    BillingController(PlanService plans, BillingService billing) {
        this.plans = plans;
        this.billing = billing;
    }

    record ActiveRequest(boolean active) {
    }

    // ---- plans ----
    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    PlanSummary createPlan(@Valid @RequestBody PlanInput input) {
        return plans.create(input);
    }

    @GetMapping("/plans")
    List<PlanSummary> listPlans() {
        return plans.list();
    }

    @PutMapping("/plans/{id}")
    PlanSummary updatePlan(@PathVariable UUID id, @Valid @RequestBody PlanInput input) {
        return plans.update(id, input);
    }

    @PatchMapping("/plans/{id}/active")
    PlanSummary setPlanActive(@PathVariable UUID id, @RequestBody ActiveRequest req) {
        return plans.setActive(id, req.active());
    }

    // ---- payments and cycles ----
    @PostMapping("/students/{studentId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    PaymentRegistered registerPayment(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID studentId,
                                      @Valid @RequestBody RegisterPaymentCommand cmd) {
        return billing.registerPayment(studentId, cmd, me.userId());
    }

    @GetMapping("/payments")
    List<PaymentSummary> payments(@RequestParam UUID studentId) {
        return billing.payments(studentId);
    }

    @GetMapping("/students/{studentId}/cycles")
    List<CycleSummary> cycles(@PathVariable UUID studentId) {
        return billing.cycles(studentId);
    }

    @GetMapping("/students/{studentId}/cycles/active")
    CycleSummary activeCycle(@PathVariable UUID studentId) {
        return billing.activeCycle(studentId).orElseThrow(CycleNotFoundException::new);
    }

    @PostMapping("/cycles/{cycleId}/extend")
    CycleSummary extend(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID cycleId,
                        @Valid @RequestBody ExtendCycleCommand cmd) {
        return billing.extendCycle(cycleId, cmd, me.userId());
    }

    @GetMapping("/billing/overview")
    List<StudentBillingOverview> overview() {
        return billing.overview();
    }
}
