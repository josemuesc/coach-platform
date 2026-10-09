package com.coachplatform.students;

import com.coachplatform.common.ApiException;
import com.coachplatform.students.api.Audience;
import com.coachplatform.students.api.AgeAlert;
import com.coachplatform.students.api.ConsentType;
import com.coachplatform.students.api.GuardianInput;
import com.coachplatform.students.api.GuardianView;
import com.coachplatform.students.api.InvitationIssued;
import com.coachplatform.students.api.StudentCreated;
import com.coachplatform.students.api.StudentInput;
import com.coachplatform.students.api.StudentSummary;
import com.coachplatform.students.domain.ConsentEvent;
import com.coachplatform.students.domain.ConsentLedger;
import com.coachplatform.students.domain.GuardianData;
import com.coachplatform.students.domain.GuardianRules;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Public API of the students module: only ids and records go out. Every query is filtered by the current tenant. */
@Service
public class StudentService {

    private final StudentRepository students;
    private final InvitationRepository invitations;
    private final ConsentEvents consentEvents;
    private final GuardianRules guardianRules;
    private final ConsentLedger ledger;
    private final Clock clock;
    private final Duration invitationTtl;

    StudentService(StudentRepository students, InvitationRepository invitations, ConsentEvents consentEvents,
                   GuardianRules guardianRules, ConsentLedger ledger, Clock clock,
                   @Value("${app.invitations.expiry-days:7}") long expiryDays) {
        this.students = students;
        this.invitations = invitations;
        this.consentEvents = consentEvents;
        this.guardianRules = guardianRules;
        this.ledger = ledger;
        this.clock = clock;
        this.invitationTtl = Duration.ofDays(expiryDays);
    }

    @Transactional
    public StudentCreated create(StudentInput input, UUID createdByUserId) {
        LocalDate birthDate = input.birthDate();
        GuardianData guardian = guardianOf(input.guardian());
        guardianRules.requireValidBirthDate(birthDate);
        guardianRules.requireGuardianData(birthDate, guardian);
        String email = loginEmail(birthDate, input.email(), guardian);
        if (students.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "STUDENT_EMAIL_EXISTS");
        }
        Student student = new Student(input.fullName().trim(), email, blankToNull(input.whatsappPhone()));
        student.updateProfile(blankToNull(input.goal()), birthDate, guardian);
        student = students.save(student);
        return new StudentCreated(toSummary(student, Set.of()), issueInvitation(student, createdByUserId));
    }

    @Transactional(readOnly = true)
    public List<StudentSummary> list() {
        Map<UUID, List<ConsentEvent>> events = consentEvents.all();
        return students.findAllByOrderByFullNameAsc().stream()
                .map(s -> toSummary(s, ledger.activeTypes(events.getOrDefault(s.getId(), List.of())))).toList();
    }

    /**
     * The student behind a logged-in STUDENT user (tenant-filtered like every query). 404 if the user is no student; 403
     * ACCOUNT_SUSPENDED if a data consent was revoked (a token issued before the suspension stops working here).
     */
    @Transactional(readOnly = true)
    public StudentSummary findByUserId(UUID userId) {
        Student student = students.findByUserId(userId).orElseThrow(StudentNotFoundException::new);
        if (student.isMarkedForAnonymization()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED");
        }
        return toSummary(student);
    }

    @Transactional(readOnly = true)
    public StudentSummary get(UUID studentId) {
        return toSummary(require(studentId));
    }

    @Transactional
    public StudentSummary update(UUID studentId, StudentInput input, Boolean active) {
        Student student = require(studentId);
        LocalDate birthDate = input.birthDate();
        GuardianData guardian = guardianOf(input.guardian());
        guardianRules.requireValidBirthDate(birthDate);
        if (student.getBirthDate() != null) {
            guardianRules.requireAudienceChangeAllowed(student.getBirthDate(), birthDate, student.hasAccount());
        }
        guardianRules.requireGuardianData(birthDate, guardian);
        String email = loginEmail(birthDate, input.email(), guardian);
        if (!email.equals(student.getEmail())) {
            if (student.hasAccount()) {
                throw new ApiException(HttpStatus.CONFLICT, "EMAIL_LOCKED");
            }
            if (students.existsByEmail(email)) {
                throw new ApiException(HttpStatus.CONFLICT, "STUDENT_EMAIL_EXISTS");
            }
        }
        student.update(input.fullName().trim(), email, blankToNull(input.whatsappPhone()));
        student.updateProfile(blankToNull(input.goal()), birthDate, guardian);
        if (active != null) {
            student.setActive(active);
        }
        return toSummary(student);
    }

    /** Revokes any open invitation and issues a fresh one. */
    @Transactional
    public InvitationIssued reissueInvitation(UUID studentId, UUID createdByUserId) {
        Student student = require(studentId);
        if (student.hasAccount()) {
            throw new ApiException(HttpStatus.CONFLICT, "STUDENT_ALREADY_HAS_ACCOUNT");
        }
        invitations.revokeOpenFor(studentId, clock.instant());
        return issueInvitation(student, createdByUserId);
    }

    /**
     * Locks the student row (SELECT ... FOR UPDATE) until the caller's transaction ends. Billing uses it to serialize
     * payments and cycle changes of one student. Must be called inside an existing transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StudentSummary lockForUpdate(UUID studentId) {
        return toSummary(students.findByIdForUpdate(studentId).orElseThrow(StudentNotFoundException::new));
    }

    private Student require(UUID studentId) {
        return students.findById(studentId).orElseThrow(StudentNotFoundException::new);
    }

    private InvitationIssued issueInvitation(Student student, UUID createdByUserId) {
        String token = InvitationToken.generate();
        var expiresAt = clock.instant().plus(invitationTtl);
        invitations.save(new Invitation(student.getId(), InvitationToken.hash(token), expiresAt, createdByUserId));
        return new InvitationIssued(token, expiresAt);
    }

    /** The login: the guardian's email while the student is under 18 (the guardian holds the account), else the student's own. */
    private String loginEmail(LocalDate birthDate, String inputEmail, GuardianData guardian) {
        if (guardianRules.isMinor(birthDate)) {
            return guardian.email();
        }
        if (inputEmail == null || inputEmail.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EMAIL_REQUIRED");
        }
        return normalizeEmail(inputEmail);
    }

    private StudentSummary toSummary(Student s) {
        return toSummary(s, ledger.activeTypes(consentEvents.of(s.getId())));
    }

    private StudentSummary toSummary(Student s, Set<ConsentType> active) {
        GuardianData g = s.guardian();
        GuardianView guardian = g.isEmpty() ? null : new GuardianView(g.name(), g.relationship(), g.phone(), g.email());
        LocalDate birth = s.getBirthDate();
        if (birth == null) {   // created before V5: no birth date yet, so no age information
            return new StudentSummary(s.getId(), s.getFullName(), s.getEmail(), s.getWhatsappPhone(), s.isActive(), s.hasAccount(),
                    s.getGoal(), null, guardian, null, false, null, 0, AgeAlert.NONE, s.isMarkedForAnonymization());
        }
        var age = guardianRules.status(birth, active);
        Audience audience = age.minor() ? Audience.GUARDIAN : Audience.ADULT;
        return new StudentSummary(s.getId(), s.getFullName(), s.getEmail(), s.getWhatsappPhone(), s.isActive(), s.hasAccount(),
                s.getGoal(), birth, guardian, audience, age.minor(), age.turnsAdultOn(), age.daysUntilAdult(), age.alert(),
                s.isMarkedForAnonymization());
    }

    private static GuardianData guardianOf(GuardianInput in) {
        return in == null ? GuardianData.NONE : new GuardianData(in.name(), in.relationship(), in.phone(), in.email());
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
