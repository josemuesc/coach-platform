package com.coachplatform.billing.api;

import java.util.UUID;

public record PlanSummary(UUID id, String name, int classesIncluded, long priceCop, boolean active) {
}
