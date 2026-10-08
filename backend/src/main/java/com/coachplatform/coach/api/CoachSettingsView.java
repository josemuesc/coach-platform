package com.coachplatform.coach.api;

public record CoachSettingsView(int cancelWindowHours, int classDurationMinutes, int expiringSoonDays,
                                int expiringSoonClasses, int maxExtensionDays, int defaultGroupCapacity) {
}
