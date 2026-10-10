package com.coachplatform.billing.api;

import java.util.UUID;

/** activeStudents = students whose CURRENT cycle (effective state) was bought with this plan; editing the plan never changes those cycles. */
public record PlanSummary(UUID id, String name, int classesIncluded, long priceCop, boolean active, Modality modality, int activeStudents) {
}
