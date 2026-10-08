package com.coachplatform.scheduling.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** A weekly window in the coach's local time: dayOfWeek 1 = Monday ... 7 = Sunday, times as "HH:mm". */
public record WindowInput(@NotNull @Min(1) @Max(7) Integer dayOfWeek, @NotBlank String start, @NotBlank String end) {
}
