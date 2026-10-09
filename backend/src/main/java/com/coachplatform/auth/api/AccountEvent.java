package com.coachplatform.auth.api;

/** What account_audit records. */
public enum AccountEvent {
    RESET_LINK_CREATED,
    RESET_LINK_USED,
    RESET_LINK_REVOKED,
    PASSWORD_CHANGED
}
