package com.coachplatform.scheduling.api;

import java.util.UUID;

public record AffectedStudent(UUID studentId, String studentName, UUID attendanceId) {
}
