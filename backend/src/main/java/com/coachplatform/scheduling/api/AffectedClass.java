package com.coachplatform.scheduling.api;

import com.coachplatform.billing.api.Modality;
import java.time.Instant;
import java.util.UUID;

/** One booked class a block would release (or released). */
public record AffectedClass(UUID attendanceId, UUID studentId, String studentName, boolean minor, UUID eventId, Instant startsAt,
                            Instant endsAt, Modality modality) {
}
