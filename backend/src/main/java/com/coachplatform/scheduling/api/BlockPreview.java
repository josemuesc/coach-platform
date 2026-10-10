package com.coachplatform.scheduling.api;

import java.util.List;

/**
 * What saving the block would do, without saving anything. {@code affected} are the classes that would be released (cancelled without
 * discount); releasedCount is their number. markedUntouched / pendingUntouched are the classes inside the block that are NOT touched:
 * already marked, and started but still to be marked.
 */
public record BlockPreview(List<AffectedClass> affected, int releasedCount, int markedUntouched, int pendingUntouched) {
}
