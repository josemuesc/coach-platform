package com.coachplatform.billing.api;

/**
 * The chip of a student on the coach's board: ONE per student. Precedence, strongest first:
 * SUSPENDIDO (account suspended), SIN_ACTIVAR (invitation not accepted yet), then by the LATEST cycle:
 * SIN_CLASES (every class used: COMPLETED, "renew"), VENCIDO (EXPIRED, "renew"), SIN_PLAN (never paid),
 * POR_VENCER (active, few days or few classes left), AL_DIA (active).
 */
public enum BoardStatus {
    SUSPENDIDO,
    SIN_ACTIVAR,
    SIN_CLASES,
    VENCIDO,
    SIN_PLAN,
    POR_VENCER,
    AL_DIA
}
