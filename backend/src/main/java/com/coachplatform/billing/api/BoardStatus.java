package com.coachplatform.billing.api;

/**
 * Where a student stands on the coach's board: ONE chip per student. Precedence, strongest first:
 * SUSPENDIDO (account suspended), SIN_ACTIVAR (invitation not accepted yet), SIN_PLAN (no cycle, or the last one expired),
 * POR_VENCER (few days or few classes left, or no classes left), AL_DIA.
 */
public enum BoardStatus {
    SUSPENDIDO,
    SIN_ACTIVAR,
    SIN_PLAN,
    POR_VENCER,
    AL_DIA
}
