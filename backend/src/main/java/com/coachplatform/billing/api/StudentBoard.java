package com.coachplatform.billing.api;

import java.util.List;

/** The coach's list of students with their cycle state: counts for the chips and the rows, by name, suspended ones last. */
public record StudentBoard(BoardCounts counts, List<BoardRow> students) {
}
