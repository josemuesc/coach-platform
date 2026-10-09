package com.coachplatform.students;

import com.coachplatform.students.domain.ConsentEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Reads the consent history (acceptances + revocations) of the current tenant as ledger events. */
@Component
class ConsentEvents {

    private final ConsentRecordRepository records;
    private final ConsentRevocationRepository revocations;

    ConsentEvents(ConsentRecordRepository records, ConsentRevocationRepository revocations) {
        this.records = records;
        this.revocations = revocations;
    }

    List<ConsentEvent> of(UUID studentId) {
        List<ConsentEvent> events = new ArrayList<>();
        records.findByStudentId(studentId).forEach(r -> events.add(accepted(r)));
        revocations.findByStudentId(studentId).forEach(r -> events.add(revoked(r)));
        return events;
    }

    /** One pass for the whole tenant: avoids a query per student when listing. */
    Map<UUID, List<ConsentEvent>> all() {
        Map<UUID, List<ConsentEvent>> byStudent = new HashMap<>();
        records.findAll().forEach(r -> byStudent.computeIfAbsent(r.getStudentId(), k -> new ArrayList<>()).add(accepted(r)));
        revocations.findAll().forEach(r -> byStudent.computeIfAbsent(r.getStudentId(), k -> new ArrayList<>()).add(revoked(r)));
        return byStudent;
    }

    static ConsentEvent accepted(ConsentRecord r) {
        return new ConsentEvent(r.getType(), ConsentEvent.Kind.ACCEPTED, r.getAcceptedAt());
    }

    static ConsentEvent revoked(ConsentRevocation r) {
        return new ConsentEvent(r.getType(), ConsentEvent.Kind.REVOKED, r.getRevokedAt());
    }
}
