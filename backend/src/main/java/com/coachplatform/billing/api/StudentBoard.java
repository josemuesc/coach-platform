package com.coachplatform.billing.api;

import java.util.List;

/**
 * The coach's list of students with their cycle state: counts for the filters and the rows. Order: students with an active cycle by name,
 * then the inactive ones (those to renew first, then the rest, suspended last), each group by name.
 */
public record StudentBoard(BoardCounts counts, List<BoardRow> students) {
}
