package com.coachplatform.students.api;

import java.time.Instant;

/**
 * The emergency contact a student registers from their own area (step 5; not built yet: the profile always carries null for now). The
 * coach sees it only on the student's profile, never in a list.
 */
public record EmergencyContactView(String name, String relationship, String phone, Instant updatedAt) {
}
