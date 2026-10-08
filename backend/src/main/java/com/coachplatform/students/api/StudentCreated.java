package com.coachplatform.students.api;

import java.time.Instant;

/** The raw invitation token is returned only here (and on re-issue); only its hash is stored. */
public record StudentCreated(StudentSummary student, InvitationIssued invitation) {
}
