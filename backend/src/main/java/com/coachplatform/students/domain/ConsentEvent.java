package com.coachplatform.students.domain;

import com.coachplatform.students.api.ConsentType;
import java.time.Instant;

/** An acceptance (a {@code consent_record} row) or a revocation (a {@code consent_revocation} row) of one consent type. */
public record ConsentEvent(ConsentType type, Kind kind, Instant at) {

    public enum Kind { ACCEPTED, REVOKED }
}
