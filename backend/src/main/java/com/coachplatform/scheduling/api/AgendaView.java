package com.coachplatform.scheduling.api;

import java.util.List;

public record AgendaView(List<EventView> events, List<SlotView> freeBlocks) {
}
