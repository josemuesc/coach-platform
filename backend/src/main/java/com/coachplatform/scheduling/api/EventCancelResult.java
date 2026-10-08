package com.coachplatform.scheduling.api;

import java.util.List;

/** The whole event was cancelled by the coach: every booked place became CANCELLED_BY_COACH (nobody is charged a class). */
public record EventCancelResult(EventView event, List<AffectedStudent> affectedStudents) {
}
