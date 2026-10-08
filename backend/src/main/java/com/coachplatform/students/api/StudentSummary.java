package com.coachplatform.students.api;

import java.util.UUID;

public record StudentSummary(UUID id, String fullName, String email, String whatsappPhone, boolean active,
                             boolean hasAccount) {
}
