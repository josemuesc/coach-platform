package com.coachplatform.coach.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Full replacement of the coach's settings. Ranges: cancellation window 0-48 h, class duration 15-180 min.
 * A duration change only affects classes booked afterwards: existing classes keep the end time they were booked with.
 */
public record UpdateCoachSettings(
        @Min(0) @Max(48) int cancelWindowHours,
        @Min(15) @Max(180) int classDurationMinutes,
        @Min(0) @Max(60) int expiringSoonDays,
        @Min(0) @Max(100) int expiringSoonClasses,
        @Min(0) @Max(365) int maxExtensionDays) {
}
