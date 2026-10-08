package com.coachplatform.billing.api;

/**
 * How a plan's classes are given. PERSONALIZED: one student per event (capacity 1, not configurable).
 * SEMI_PERSONALIZED: several students share the event (capacity from the coach's settings, 2-10).
 * Prices are different plans configured by the coach; the code has no pricing logic by modality.
 */
public enum Modality {
    PERSONALIZED,
    SEMI_PERSONALIZED
}
