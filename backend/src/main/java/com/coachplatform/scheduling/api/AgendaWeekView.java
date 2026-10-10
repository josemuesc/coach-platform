package com.coachplatform.scheduling.api;

import java.time.LocalDate;
import java.util.List;

/** Monday to Sunday of the week that holds the requested date. */
public record AgendaWeekView(LocalDate weekStart, List<AgendaDay> days) {
}
