package com.coachplatform.students;

import com.coachplatform.auth.AuthService;
import com.coachplatform.common.ApiException;
import com.coachplatform.coach.CoachService;
import com.coachplatform.students.api.InvitationAccepted;
import com.coachplatform.students.api.Audience;
import com.coachplatform.students.api.ConsentTextView;
import com.coachplatform.students.api.InvitationPreview;
import com.coachplatform.students.domain.ConsentGrant;
import com.coachplatform.students.domain.ConsentRules;
import com.coachplatform.students.domain.GuardianRules;
import com.coachplatform.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final ConsentCatalog catalog;
    private final ConsentRecordRepository consents;
    private final ConsentRules consentRules;
    private final GuardianRules guardianRules;

    InvitationService(InvitationTenantLookup tenantLookup, InvitationRepository invitations, StudentRepository students,
                      AuthService auth, CoachService coaches, TransactionTemplate tx, Clock clock,
                      ConsentCatalog catalog, ConsentRecordRepository consents, ConsentRules consentRules, GuardianRules guardianRules) {
        this.tenantLookup = tenantLookup;
        this.invitations = invitations;
        this.students = students;
        this.auth = auth;
        this.coaches = coaches;
        this.tx = tx;
        this.clock = clock;
        this.catalog = catalog;
        this.consents = consents;
        this.consentRules = consentRules;
        this.guardianRules = guardianRules;
    }

    public InvitationPreview preview(String token) {
        String hash = InvitationToken.hash(token);
        UUID coachId = tenantLookup.coachIdIfUsable(hash, clock.instant()).orElseThrow(InvalidInvitationException::new);
        return TenantContext.callAs(coachId, () -> tx.execute(status -> {
            Invitation invitation = usable(hash);
            Student student = students.findById(invitation.getStudentId()).orElseThrow(InvalidInvitationException::new);
            Audience audience = audienceOf(student);
            String phone = student.getBirthDate() == null ? student.getWhatsappPhone()
                    : guardianRules.notificationPhone(student.getBirthDate(), Set.of(), student.getWhatsappPhone(), student.guardian());
            Map<String, String> values = Map.of("NOMBRE_DEL_MENOR", student.getFullName(),
                    "CELULAR", phone == null ? "el numero de contacto registrado" : phone);
            List<ConsentTextView> texts = ConsentRules.offeredFor(audience).stream().map(t -> catalog.view(t, values)).toList();
            var brand = coaches.brand(coachId);
            return new InvitationPreview(brand.brandName(), brand.primaryColor(), student.getFullName(), student.getEmail(), audience,
                    audience == Audience.GUARDIAN ? student.guardian().name() : null, texts);
        }));
    }

    /** The student chooses the password here. Any failure rolls everything back, leaving the invitation unused. */
    public InvitationAccepted accept(String token, String password, boolean acceptData, String dataVersion,
                                     boolean acceptWhatsapp, String whatsappVersion) {
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
            // the consents are validated BEFORE the account exists: a stale version or a declined data authorization rolls
            // everything back, the invitation stays unused
            List<ConsentGrant> grants = consentRules.grants(audienceOf(student), catalog.offers(), acceptData, dataVersion,
                    acceptWhatsapp, whatsappVersion, student.guardian());
            grants.forEach(g -> ConsentRules.requireMayRegister(g.type(), ConsentRules.Channel.INVITATION, true));
            UUID userId;
            try {
                userId = auth.createStudentAccount(coachId, student.getEmail(), password);
            } catch (ApiException e) {
                // the guardian already has an account (e.g. with another coach): say so in terms the coach understands
                if ("EMAIL_ALREADY_USED".equals(e.code()) && audienceOf(student) == Audience.GUARDIAN) {
                    throw new GuardianEmailInUseException();
                }
                throw e;
            }
            student.linkAccount(userId);
            Instant now = clock.instant();
            for (ConsentGrant grant : grants) {
                consents.save(new ConsentRecord(student.getId(), grant, now, userId));
                student.reflectAcceptance(grant.type(), now);
            }
            return new InvitationAccepted(student.getEmail());
        }));
    }

    private Audience audienceOf(Student student) {
        return student.getBirthDate() == null ? Audience.ADULT : guardianRules.audienceFor(student.getBirthDate());
    }

    private Invitation usable(String hash) {
        Invitation invitation = invitations.findByTokenHash(hash).orElseThrow(InvalidInvitationException::new);
        if (!invitation.isUsable(clock.instant())) {
            throw new InvalidInvitationException();
        }
        return invitation;
    }
}
