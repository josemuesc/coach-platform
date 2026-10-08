package com.coachplatform.students.domain;

import com.coachplatform.students.api.ConsentType;

/**
 * One authorization to record in {@code consent_record}: type, version and hash of the text that was accepted. For
 * DATA_GUARDIAN it also carries the signer (the guardian on file at that moment); otherwise the signer is null.
 */
public record ConsentGrant(ConsentType type, String version, String textSha256, String signerName, String signerRelationship) {
}
