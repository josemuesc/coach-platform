package com.coachplatform.coach.api;

/**
 * Per-coach billing settings (coach_settings).
 * expiringSoon*: a cycle is "about to expire" when it has this many days or fewer, or this many classes or fewer, left.
 * maxExtensionDays: how far past the ORIGINAL deadline the coach may extend a cycle, in total.
 */
public record BillingSettings(int expiringSoonDays, int expiringSoonClasses, int maxExtensionDays) {
}
