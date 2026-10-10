package com.coachplatform.scheduling.api;

import com.coachplatform.billing.api.CycleOverview;
import com.coachplatform.students.api.EmergencyContactView;
import com.coachplatform.students.api.StudentAccountView;
import com.coachplatform.students.api.StudentConsentsView;
import com.coachplatform.students.api.StudentSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Everything the coach's student screen needs, in ONE answer. {@code upcoming} holds up to 5 booked classes that have not ended, soonest
 * first. {@code emergencyContact} is ALWAYS null until the student can register one from their own area (step 5). {@code cycle} carries the latest cycle (null inside when the student never paid) and what the coach may do about it.
 */
public record StudentProfile(StudentSummary student, StudentAccountView account, CycleOverview cycle, List<UpcomingClass> upcoming,
                             StudentConsentsView consents,
                             @Schema(nullable = true) EmergencyContactView emergencyContact) {
}
