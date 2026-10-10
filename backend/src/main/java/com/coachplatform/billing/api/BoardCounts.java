package com.coachplatform.billing.api;

/**
 * The numbers on the board's filters. {@code all} = every student listed (suspended ones included); {@code active} = students with an
 * ACTIVE cycle; {@code expiring} = the active ones about to expire (a subset of {@code active}); {@code inactive} = everyone else
 * (never paid, expired, all classes used, or suspended): {@code all = active + inactive}.
 */
public record BoardCounts(int all, int expiring, int active, int inactive) {
}
