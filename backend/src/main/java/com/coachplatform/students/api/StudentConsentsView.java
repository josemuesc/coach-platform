package com.coachplatform.students.api;

import java.util.List;
import java.util.UUID;

public record StudentConsentsView(UUID studentId, Audience audience, boolean anonymizationRequested, List<ConsentItemView> consents) {
}
