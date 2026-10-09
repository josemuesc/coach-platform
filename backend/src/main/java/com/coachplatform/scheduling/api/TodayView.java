package com.coachplatform.scheduling.api;

import java.util.List;

/** The coach's events of today (America/Bogota), with attendees, status, confirmation and free seats. */
public record TodayView(String date, List<EventView> events) {
}
