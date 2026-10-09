package com.coachplatform.auth.api;

/** How the last password change happened. */
public enum PasswordChangeMethod {
    /** The user changed it while logged in, knowing the previous one. */
    SELF,
    /** Set through a one-time reset link the coach generated and handed over. */
    COACH_LINK
}
