package com.coachplatform.students.domain;

import com.coachplatform.students.api.ConsentType;

/** The text currently in force for one consent type: its version and the SHA-256 of the exact text shown. */
public record ConsentOffer(ConsentType type, String version, String textSha256) {
}
