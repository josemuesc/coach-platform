package com.coachplatform.students.api;

import java.time.Instant;

/**
 * The current state of one consent type. {@code upToDate} is false when the text in force is newer than the accepted one.
 * {@code acceptedVersion}/{@code acceptedAt} describe the acceptance in force (null when not active).
 */
public record ConsentItemView(ConsentType type, boolean applies, boolean required, boolean active, String acceptedVersion,
                              Instant acceptedAt, String currentVersion, boolean upToDate) {
}
