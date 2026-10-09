package com.coachplatform.coach.api;

import java.time.Instant;

public record CoachSettingsView(int cancelWindowHours, int classDurationMinutes, int expiringSoonDays,
                                int expiringSoonClasses, int maxExtensionDays, int defaultGroupCapacity,
                                int confirmationWindowHours, int qrOpenMinutesBefore, int qrCloseHoursAfterEnd,
                                boolean gymConsentConfirmed, Instant gymConsentConfirmedAt) {
}
