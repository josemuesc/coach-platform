package com.coachplatform.billing.api;

/**
 * Why a student is "about to expire": {@code DAYS} (the deadline is near), {@code CLASSES} (few classes left, or none: a COMPLETED cycle)
 * or {@code BOTH}. The coach's thresholds decide "near" and "few" (5 days / 1 class by default).
 */
public enum ExpiringBy {
    DAYS,
    CLASSES,
    BOTH
}
