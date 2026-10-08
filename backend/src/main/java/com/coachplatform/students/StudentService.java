package com.coachplatform.students;

import com.coachplatform.common.ApiException;
import com.coachplatform.students.api.InvitationIssued;
import com.coachplatform.students.api.StudentCreated;
import com.coachplatform.students.api.StudentInput;
import com.coachplatform.students.api.StudentSummary;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
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
    private final Clock clock;
    private final Duration invitationTtl;

    public StudentService(StudentRepository students, InvitationRepository invitations, Clock clock,
                          @Value("${app.invitations.expiry-days:7}") long expiryDays) {
        this.students = students;
        this.invitations = invitations;
        this.clock = clock;
        this.invitationTtl = Duration.ofDays(expiryDays);
    }

    @Transactional
    public StudentCreated create(StudentInput input, UUID createdByUserId) {
        String email = normalizeEmail(input.email());
        if (students.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "STUDENT_EMAIL_EXISTS");
        }
        Student student = students.save(new Student(input.fullName().trim(), email, blankToNull(input.whatsappPhone())));
        return new StudentCreated(toSummary(student), issueInvitation(student, createdByUserId));
    }

    @Transactional(readOnly = true)
    public List<StudentSummary> list() {
        return students.findAllByOrderByFullNameAsc().stream().map(StudentService::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public StudentSummary get(UUID studentId) {
        return toSummary(require(studentId));
    }

    @Transactional
    public StudentSummary update(UUID studentId, StudentInput input, Boolean active) {
        Student student = require(studentId);
        String email = normalizeEmail(input.email());
        if (!email.equals(student.getEmail())) {
            if (student.hasAccount()) {
                throw new ApiException(HttpStatus.CONFLICT, "EMAIL_LOCKED");
            }
            if (students.existsByEmail(email)) {
                throw new ApiException(HttpStatus.CONFLICT, "STUDENT_EMAIL_EXISTS");
            }
        }
        student.update(input.fullName().trim(), email, blankToNull(input.whatsappPhone()));
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

    private static StudentSummary toSummary(Student s) {
        return new StudentSummary(s.getId(), s.getFullName(), s.getEmail(), s.getWhatsappPhone(), s.isActive(),
                s.hasAccount());
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
