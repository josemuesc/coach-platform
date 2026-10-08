package com.coachplatform.coach.api;

/** What scheduling needs from the coach's settings. */
public record SchedulingSettings(int cancelWindowHours, int classDurationMinutes) {
}
