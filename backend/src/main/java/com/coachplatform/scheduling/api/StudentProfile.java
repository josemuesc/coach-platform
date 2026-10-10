package com.coachplatform.scheduling.api;

import com.coachplatform.billing.api.CycleOverview;
import com.coachplatform.students.api.StudentAccountView;
import com.coachplatform.students.api.StudentConsentsView;
import com.coachplatform.students.api.StudentSummary;
import java.util.List;

/**
 * Everything the coach's student screen needs, in ONE answer. {@code upcoming} holds up to 5 booked classes that have not ended, soonest
 * first. {@code cycle} carries the latest cycle (null inside when the student never paid) and what the coach may do about it.
 */
public record StudentProfile(StudentSummary student, StudentAccountView account, CycleOverview cycle, List<UpcomingClass> upcoming,
                             StudentConsentsView consents) {
}
