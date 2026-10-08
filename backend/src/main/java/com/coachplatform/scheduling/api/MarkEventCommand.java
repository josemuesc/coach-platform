package com.coachplatform.scheduling.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** Several students of one event at once. All or nothing: if any mark is invalid, none is applied. */
public record MarkEventCommand(@NotEmpty List<@Valid MarkItem> marks) {
}
