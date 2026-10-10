package com.coachplatform.billing.api;

/**
 * Why an ACTIVE cycle is "about to expire": {@code DAYS} (the deadline is near), {@code CLASSES} (few classes left) or {@code BOTH}.
 * The coach's thresholds decide "near" and "few" (5 days / 1 class by default). A cycle with no classes left is COMPLETED, not active.
 */
public enum ExpiringBy {
    DAYS,
    CLASSES,
    BOTH
}
