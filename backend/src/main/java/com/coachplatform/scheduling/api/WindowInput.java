package com.coachplatform.scheduling.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** A weekly window in the coach's local time: dayOfWeek 1 = Monday ... 7 = Sunday, times as "HH:mm". */
public record WindowInput(@Min(1) @Max(7) int dayOfWeek, @NotBlank String start, @NotBlank String end) {
}
