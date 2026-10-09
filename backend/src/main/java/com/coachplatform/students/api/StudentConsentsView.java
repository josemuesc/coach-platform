package com.coachplatform.students.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

public record StudentConsentsView(UUID studentId, @Schema(nullable = true) Audience audience, boolean anonymizationRequested, List<ConsentItemView> consents) {
}
