package com.coachplatform.auth;

import com.coachplatform.auth.api.AccountEvent;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes account_audit lines in the caller's transaction, with the time of the injected Clock. */
@Service
public class AccountAuditService {

    private final AccountAuditRepository audits;
    private final Clock clock;

    AccountAuditService(AccountAuditRepository audits, Clock clock) {
        this.audits = audits;
        this.clock = clock;
    }

    /**
     * @param actorUserId the coach for a link created or revoked; the account itself when it uses a link or changes its password
     * @param studentId   the student the link belongs to (null for PASSWORD_CHANGED)
     * @param resetId     the reset link (null for PASSWORD_CHANGED)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AccountEvent event, UUID targetUserId, UUID actorUserId, UUID studentId, UUID resetId) {
        audits.save(new AccountAudit(event, targetUserId, actorUserId, studentId, resetId, clock.instant()));
    }
}
