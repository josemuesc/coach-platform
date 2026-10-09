package com.coachplatform.students;

import com.coachplatform.auth.AuthService;
import com.coachplatform.common.ApiException;
import com.coachplatform.students.api.Audience;
import com.coachplatform.students.api.ConsentItemView;
import com.coachplatform.students.api.ConsentType;
import com.coachplatform.students.api.StudentConsentsView;
import com.coachplatform.students.domain.ConsentEvent;
import com.coachplatform.students.domain.ConsentGrant;
import com.coachplatform.students.domain.ConsentLedger;
import com.coachplatform.students.domain.ConsentRevocationEffects;
import com.coachplatform.students.domain.ConsentRevocationEffects.Effect;
import com.coachplatform.students.domain.ConsentRules;
import com.coachplatform.students.domain.ConsentRules.Channel;
import com.coachplatform.students.domain.GuardianRules;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepting, revoking and reading the consents of a student. Every change runs under the student's row lock (SELECT ... FOR
 * UPDATE), so two simultaneous changes cannot both pass the "already in force / nothing to revoke" check. Timestamps come
 * from the injected Clock. Revoking WHATSAPP stops notifications; revoking the last DATA consent suspends the account and
 * marks the student for anonymization (consent records and the audit are kept).
 */
@Service
public class ConsentService {

    private final StudentRepository students;
    private final ConsentRecordRepository records;
    private final ConsentRevocationRepository revocations;
    private final ConsentEvents consentEvents;
    private final ConsentCatalog catalog;
    private final GuardianRules guardianRules;
    private final ConsentLedger ledger;
    private final AuthService auth;
    private final Clock clock;

    ConsentService(StudentRepository students, ConsentRecordRepository records, ConsentRevocationRepository revocations,
                   ConsentEvents consentEvents, ConsentCatalog catalog, GuardianRules guardianRules, ConsentLedger ledger,
                   AuthService auth, Clock clock) {
        this.students = students;
        this.records = records;
        this.revocations = revocations;
        this.consentEvents = consentEvents;
        this.catalog = catalog;
        this.guardianRules = guardianRules;
        this.ledger = ledger;
        this.auth = auth;
        this.clock = clock;
    }

    // ---- reading ----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public StudentConsentsView forStudent(UUID studentId) {
        return view(requireStudent(studentId));
    }

    /** The logged-in student's own consents. */
    @Transactional(readOnly = true)
    public StudentConsentsView mine(UUID userId) {
        return view(studentOfUser(userId));
    }

    // ---- the student (or the guardian holding the account) ---------------------------------------------------------

    @Transactional
    public StudentConsentsView acceptAsStudent(UUID userId, ConsentType type, String version) {
        return accept(lockedStudentOfUser(userId), type, version, Channel.STUDENT_SESSION, userId);
    }

    @Transactional
    public StudentConsentsView revokeAsStudent(UUID userId, ConsentType type) {
        return revoke(lockedStudentOfUser(userId), type, userId);
    }

    // ---- the coach ------------------------------------------------------------------------------------------

    /** The coach registers the guardian's authorization (the physical one they hold). Other types are refused. */
    @Transactional
    public StudentConsentsView acceptAsCoach(UUID studentId, ConsentType type, String version, UUID coachUserId) {
        return accept(lockedStudent(studentId), type, version, Channel.COACH_SESSION, coachUserId);
    }

    /** The coach records a revocation the guardian or the student asked for. */
    @Transactional
    public StudentConsentsView revokeAsCoach(UUID studentId, ConsentType type, UUID coachUserId) {
        return revoke(lockedStudent(studentId), type, coachUserId);
    }

    // ---- internals --------------------------------------------------------------------------------------------

    private StudentConsentsView accept(Student student, ConsentType type, String version, Channel channel, UUID byUserId) {
        List<ConsentEvent> events = consentEvents.of(student.getId());
        Set<ConsentType> active = ledger.activeTypes(events);
        Audience audience = audienceOf(student);
        ConsentRules.requireTypeApplies(type, audience);
        ConsentRules.requireMayRegister(type, channel, guardianHolder(student, active));
        ConsentRevocationEffects.requireMayAcceptData(type, student.isMarkedForAnonymization());
        ledger.requireMayAccept(type, events);
        ConsentGrant grant = ConsentRules.grant(catalog.offer(type), version, student.guardian());
        Instant now = clock.instant();
        records.save(new ConsentRecord(student.getId(), grant, now, byUserId));
        student.reflectAcceptance(type, now);
        return view(student);
    }

    private StudentConsentsView revoke(Student student, ConsentType type, UUID byUserId) {
        List<ConsentEvent> events = consentEvents.of(student.getId());
        ledger.requireMayRevoke(type, events);
        Instant now = clock.instant();
        revocations.save(new ConsentRevocation(student.getId(), type, now, byUserId));
        List<ConsentEvent> after = new ArrayList<>(events);
        after.add(new ConsentEvent(type, ConsentEvent.Kind.REVOKED, now));
        Set<ConsentType> stillActive = ledger.activeTypes(after);
        Set<Effect> effects = ConsentRevocationEffects.of(type, stillActive);
        student.reflectRevocation(type, stillActive.stream().anyMatch(ConsentRevocationEffects::isData));
        if (effects.contains(Effect.SUSPEND_ACCOUNT)) {
            student.suspendForAnonymization(now);
            if (student.getUserId() != null) {
                auth.deactivateAccount(student.getUserId());
            }
        }
        return view(student);
    }

    private StudentConsentsView view(Student student) {
        List<ConsentEvent> events = consentEvents.of(student.getId());
        Set<ConsentType> active = ledger.activeTypes(events);
        Audience audience = audienceOf(student);
        List<ConsentRecord> accepted = records.findByStudentId(student.getId());
        List<ConsentItemView> items = new ArrayList<>();
        for (ConsentType type : ConsentType.values()) {
            boolean applies = type == ConsentType.WHATSAPP || type == ConsentRules.dataTypeFor(audience);
            boolean isActive = active.contains(type);
            if (!applies && !isActive) {
                continue;
            }
            ConsentRecord latest = accepted.stream().filter(r -> r.getType() == type)
                    .max(Comparator.comparing(ConsentRecord::getAcceptedAt)).orElse(null);
            String currentVersion = catalog.offer(type).version();
            boolean upToDate = isActive && latest != null && latest.getVersion().equals(currentVersion);
            items.add(new ConsentItemView(type, applies, ConsentRules.isRequired(type), isActive,
                    isActive && latest != null ? latest.getVersion() : null,
                    isActive && latest != null ? latest.getAcceptedAt() : null, currentVersion, upToDate));
        }
        return new StudentConsentsView(student.getId(), audience, student.isMarkedForAnonymization(), items);
    }

    private Audience audienceOf(Student student) {
        return student.getBirthDate() == null ? Audience.ADULT : guardianRules.audienceFor(student.getBirthDate());
    }

    private boolean guardianHolder(Student student, Set<ConsentType> active) {
        return student.getBirthDate() != null && guardianRules.guardianIsHolder(student.getBirthDate(), active);
    }

    private Student requireStudent(UUID studentId) {
        return students.findById(studentId).orElseThrow(StudentNotFoundException::new);
    }

    private Student lockedStudent(UUID studentId) {
        return students.findByIdForUpdate(studentId).orElseThrow(StudentNotFoundException::new);
    }

    private Student studentOfUser(UUID userId) {
        Student student = students.findByUserId(userId).orElseThrow(StudentNotFoundException::new);
        if (student.isMarkedForAnonymization()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED");
        }
        return student;
    }

    /** Resolves the student of the token WITHOUT loading it first, then locks it: no stale copy in the persistence cache. */
    private Student lockedStudentOfUser(UUID userId) {
        UUID id = students.findIdByUserId(userId).orElseThrow(StudentNotFoundException::new);
        Student student = lockedStudent(id);
        if (student.isMarkedForAnonymization()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED");
        }
        return student;
    }
}
