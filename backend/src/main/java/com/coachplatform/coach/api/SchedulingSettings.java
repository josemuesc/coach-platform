package com.coachplatform.coach.api;

/** What scheduling needs from the coach's settings. defaultGroupCapacity applies only to events created from now on. */
public record SchedulingSettings(int cancelWindowHours, int classDurationMinutes, int defaultGroupCapacity) {
}
