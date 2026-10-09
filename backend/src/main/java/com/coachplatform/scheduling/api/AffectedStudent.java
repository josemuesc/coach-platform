package com.coachplatform.scheduling.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record AffectedStudent(UUID studentId, @Schema(nullable = true) String studentName, UUID attendanceId) {
}
