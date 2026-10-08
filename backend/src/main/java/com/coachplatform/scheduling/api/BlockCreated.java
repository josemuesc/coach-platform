package com.coachplatform.scheduling.api;

import java.util.List;

/**
 * A block never cancels anything: classes already booked inside it are only LISTED here so the coach can
 * cancel or move them one by one.
 */
public record BlockCreated(BlockSummary block, List<SessionSummary> affectedSessions) {
}
