package com.coachplatform.coach.api;

/**
 * What scheduling needs from the coach's settings. defaultGroupCapacity applies only to events created from now on.
 * confirmationWindowHours: how long after the start a student may still confirm a class from their history.
 * qrOpenMinutesBefore / qrCloseHoursAfterEnd: the coach can show the event's code from that long before the start until that
 * long after the end; a scan also stops counting after the latter.
 */
public record SchedulingSettings(int cancelWindowHours, int classDurationMinutes, int defaultGroupCapacity,
                                 int confirmationWindowHours, int qrOpenMinutesBefore, int qrCloseHoursAfterEnd) {
}
