package com.coachplatform.scheduling.api;

import java.util.List;

public record AgendaView(List<SessionSummary> sessions, List<SlotView> freeSlots) {
}
