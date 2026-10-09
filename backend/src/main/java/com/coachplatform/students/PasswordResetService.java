package com.coachplatform.students;

import com.coachplatform.auth.AccountAuditService;
import com.coachplatform.auth.AuthService;
import com.coachplatform.auth.api.AccountEvent;
import com.coachplatform.common.ApiException;
import com.coachplatform.common.ConcurrentChangeException;
import com.coachplatform.students.api.PasswordResetIssued;
import com.coachplatform.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Password recovery WITHOUT e-mail: the coach generates a one-time link for a student's login (the guardian's, for a minor) and
 * hands it over; whoever opens it chooses the new password. Every step is audited (who, when), the token is only stored hashed,
 * a student has at most one open link, and using the link ends every earlier session of that login.
 */
@Service
public class PasswordResetService {

    private final StudentRepository students;
    private final PasswordResetRepository resets;
    private final PasswordResetTenantLookup tenantLookup;
    private final AuthService auth;
    private final AccountAuditService accountAudit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration ttl;

    PasswordResetService(StudentRepository students, PasswordResetRepository resets, PasswordResetTenantLookup tenantLookup,
                         AuthService auth, AccountAuditService accountAudit, TransactionTemplate tx, Clock clock,
                         @Value("${app.password-reset.expiry-hours:24}") long expiryHours) {
        this.students = students;
        this.resets = resets;
        this.tenantLookup = tenantLookup;
        this.auth = auth;
        this.accountAudit = accountAudit;
        this.tx = tx;
        this.clock = clock;
        this.ttl = Duration.ofHours(expiryHours);
    }

    /** Revokes any open link of that login and issues a fresh one. The token is returned this once and never again. */
    @Transactional
    public PasswordResetIssued issue(UUID studentId, UUID coachUserId) {
        Student student = students.findByIdForUpdate(studentId).orElseThrow(StudentNotFoundException::new);
        if (!student.hasAccount()) {
            throw new ApiException(HttpStatus.CONFLICT, "STUDENT_HAS_NO_ACCOUNT");
        }
        if (student.isMarkedForAnonymization()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED");
        }
        UUID userId = student.getUserId();
        auth.requireActiveAccount(userId);
        revokeOpen(student, userId, coachUserId);
        String token = InvitationToken.generate();
        Instant now = clock.instant();
        PasswordReset reset;
        try {
            reset = resets.saveAndFlush(new PasswordReset(studentId, userId, InvitationToken.hash(token), coachUserId, now, now.plus(ttl)));
        } catch (DataIntegrityViolationException e) {
            // The student's row lock already serializes this; if the one-open-link index still trips, answer 409 (never a 500)
            throw new ConcurrentChangeException();
        }
        accountAudit.record(AccountEvent.RESET_LINK_CREATED, userId, coachUserId, studentId, reset.getId());
        return new PasswordResetIssued(token, reset.getExpiresAt());
    }

    /** Cancels the open link, if any (e.g. it was handed to the wrong person). Idempotent. */
    @Transactional
    public void revoke(UUID studentId, UUID coachUserId) {
        Student student = students.findByIdForUpdate(studentId).orElseThrow(StudentNotFoundException::new);
        if (student.hasAccount()) {
            revokeOpen(student, student.getUserId(), coachUserId);
        }
    }

    /**
     * The public side. The tenant comes from the token's hash; the link is consumed atomically (it works exactly once), the new
     * password is set and the use is audited, all in one transaction: if anything fails the link stays usable.
     */
    public void redeem(String token, String newPassword) {
        String hash = InvitationToken.hash(token);
        UUID coachId = tenantLookup.coachIdIfUsable(hash, clock.instant()).orElseThrow(InvalidResetLinkException::new);
        TenantContext.runAs(coachId, () -> tx.executeWithoutResult(status -> {
            if (resets.consume(hash, clock.instant()) != 1) {
                throw new InvalidResetLinkException();
            }
            PasswordReset reset = resets.findByTokenHash(hash).orElseThrow(InvalidResetLinkException::new);
            auth.applyPasswordReset(reset.getUserId(), newPassword);
            accountAudit.record(AccountEvent.RESET_LINK_USED, reset.getUserId(), reset.getUserId(), reset.getStudentId(), reset.getId());
        }));
    }

    private void revokeOpen(Student student, UUID userId, UUID coachUserId) {
        Instant now = clock.instant();
        for (PasswordReset open : resets.findOpenByUserId(userId)) {
            open.revoke(now);
            resets.saveAndFlush(open);   // flushed BEFORE a new link is inserted: the unique index allows one open link per login
            accountAudit.record(AccountEvent.RESET_LINK_REVOKED, userId, coachUserId, student.getId(), open.getId());
        }
    }
}
