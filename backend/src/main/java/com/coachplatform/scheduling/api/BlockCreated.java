package com.coachplatform.scheduling.api;

import java.util.List;

/**
 * A block never cancels anything: events already booked inside it are only LISTED here (with their attendees) so the
 * coach can cancel or move them one by one.
 */
public record BlockCreated(BlockSummary block, List<EventView> affectedEvents) {
}
