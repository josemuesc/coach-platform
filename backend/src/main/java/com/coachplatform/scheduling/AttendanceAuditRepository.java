package com.coachplatform.scheduling;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AttendanceAuditRepository extends JpaRepository<AttendanceAudit, UUID> {

    List<AttendanceAudit> findByAttendanceIdOrderByOccurredAtAscIdAsc(UUID attendanceId);
}
