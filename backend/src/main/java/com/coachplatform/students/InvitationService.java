package com.coachplatform.students;

import com.coachplatform.auth.AuthService;
import com.coachplatform.coach.CoachService;
import com.coachplatform.students.api.InvitationAccepted;
import com.coachplatform.students.api.InvitationPreview;
import com.coachplatform.tenant.TenantContext;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Public invitation flow (no logged-in user). The tenant is derived from the token's hash, the whole operation then
 * runs as that tenant, and the token is consumed atomically so it works exactly once.
 */
@Service
public class InvitationService {

    private final InvitationTenantLookup tenantLookup;
    private final InvitationRepository invitations;
    private final StudentRepository students;
    private final AuthService auth;
    private final CoachService coaches;
    private final TransactionTemplate tx;
    private final Clock clock;

    InvitationService(InvitationTenantLookup tenantLookup, InvitationRepository invitations, StudentRepository students,
                      AuthService auth, CoachService coaches, TransactionTemplate tx, Clock clock) {
        this.tenantLookup = tenantLookup;
        this.invitations = invitations;
        this.students = students;
        this.auth = auth;
        this.coaches = coaches;
        this.tx = tx;
        this.clock = clock;
    }

    public InvitationPreview preview(String token) {
        String hash = InvitationToken.hash(token);
        UUID coachId = tenantLookup.coachIdIfUsable(hash, clock.instant()).orElseThrow(InvalidInvitationException::new);
        return TenantContext.callAs(coachId, () -> tx.execute(status -> {
            Invitation invitation = usable(hash);
            Student student = students.findById(invitation.getStudentId()).orElseThrow(InvalidInvitationException::new);
            return new InvitationPreview(coaches.brandName(coachId), student.getFullName());
        }));
    }

    /** The student chooses the password here. Any failure rolls everything back, leaving the invitation unused. */
    public InvitationAccepted accept(String token, String password) {
        String hash = InvitationToken.hash(token);
        UUID coachId = tenantLookup.coachIdIfUsable(hash, clock.instant()).orElseThrow(InvalidInvitationException::new);
        return TenantContext.callAs(coachId, () -> tx.execute(status -> {
            Invitation invitation = invitations.findByTokenHash(hash).orElseThrow(InvalidInvitationException::new);
            if (invitations.consume(hash, clock.instant()) != 1) {
                throw new InvalidInvitationException();
            }
            Student student = students.findById(invitation.getStudentId()).orElseThrow(InvalidInvitationException::new);
            if (student.hasAccount()) {
                throw new InvalidInvitationException();
            }
            UUID userId = auth.createStudentAccount(coachId, student.getEmail(), password);
            student.linkAccount(userId);
            return new InvitationAccepted(student.getEmail());
        }));
    }

    private Invitation usable(String hash) {
        Invitation invitation = invitations.findByTokenHash(hash).orElseThrow(InvalidInvitationException::new);
        if (!invitation.isUsable(clock.instant())) {
            throw new InvalidInvitationException();
        }
        return invitation;
    }
}
