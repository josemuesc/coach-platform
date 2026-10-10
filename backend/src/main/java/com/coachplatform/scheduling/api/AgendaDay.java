package com.coachplatform.scheduling.api;

import java.time.LocalDate;
import java.util.List;

/** classCount = events of the day; freeCount = free slots; hasAvailability = the weekly schedule has a window for this weekday. */
public record AgendaDay(LocalDate localDate, boolean hasAvailability, int classCount, int freeCount, List<AgendaRow> items) {
}
