package com.coachplatform.billing.api;

/** The numbers on the board's chips. They count ACTIVE students only (a suspended student is listed but counted nowhere). */
public record BoardCounts(int all, int expiring, int noPlan, int minors) {
}
