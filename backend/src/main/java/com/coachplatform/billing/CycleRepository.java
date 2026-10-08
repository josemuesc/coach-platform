package com.coachplatform.billing;

import com.coachplatform.billing.api.CycleStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CycleRepository extends JpaRepository<Cycle, UUID> {

    Optional<Cycle> findFirstByStudentIdOrderByStartDateDescCreatedAtDesc(UUID studentId);

    Optional<Cycle> findFirstByStudentIdAndStatus(UUID studentId, CycleStatus status);

    List<Cycle> findByStudentIdOrderByStartDateDescCreatedAtDesc(UUID studentId);

    List<Cycle> findByStatus(CycleStatus status);
}
