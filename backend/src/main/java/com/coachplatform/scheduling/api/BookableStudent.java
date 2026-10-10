package com.coachplatform.scheduling.api;

import com.coachplatform.billing.api.Modality;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * An active student as the "book a class" selector lists them. classesAvailable = classes of the active cycle still free to book
 * (included - used - already booked), 0 when there is none. modality is the ACTIVE cycle's (null without one).
 */
public record BookableStudent(UUID studentId, String fullName, boolean minor, @Schema(nullable = true) Modality modality,
                              int classesAvailable, boolean canBook, @Schema(nullable = true) BookBlockedReason blockedReason) {
}
