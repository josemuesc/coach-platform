package com.coachplatform.students.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * The current state of one consent type. {@code upToDate} is false when the text in force is newer than the accepted one.
 * {@code acceptedVersion}/{@code acceptedAt} describe the acceptance in force (null when not active).
 */
public record ConsentItemView(ConsentType type, boolean applies, boolean required, boolean active, @Schema(nullable = true) String acceptedVersion,
                              @Schema(nullable = true) Instant acceptedAt, String currentVersion, boolean upToDate) {
}
