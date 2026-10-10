package com.coachplatform.scheduling.api;

/** The rule an exception would relax to take a slot. */
public enum OverrideRule {
    MODALITY_MISMATCH,
    EVENT_FULL,
    SLOT_TAKEN
}
