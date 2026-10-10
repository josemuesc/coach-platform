package com.coachplatform.scheduling.api;

import jakarta.validation.constraints.NotBlank;

/** A window of ONE weekday, in the coach's local time ("HH:mm"). */
public record DayWindowInput(@NotBlank String start, @NotBlank String end) {
}
