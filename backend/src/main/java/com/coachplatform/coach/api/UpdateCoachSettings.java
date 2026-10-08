package com.coachplatform.coach.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Full replacement of the coach's settings: EVERY field is mandatory (a missing one is a 400, never a silent 0).
 * Ranges: cancellation window 0-48 h, class duration 15-180 min, group capacity 2-10.
 * A duration change only affects classes booked afterwards: existing classes keep the end time they were booked with.
 * defaultGroupCapacity is the capacity of semi-personalized events created from now on; existing events keep theirs.
 */
public record UpdateCoachSettings(
        @NotNull @Min(0) @Max(48) Integer cancelWindowHours,
        @NotNull @Min(15) @Max(180) Integer classDurationMinutes,
        @NotNull @Min(0) @Max(60) Integer expiringSoonDays,
        @NotNull @Min(0) @Max(100) Integer expiringSoonClasses,
        @NotNull @Min(0) @Max(365) Integer maxExtensionDays,
        @NotNull @Min(2) @Max(10) Integer defaultGroupCapacity) {
}
