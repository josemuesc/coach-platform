package com.coachplatform.billing;

import com.coachplatform.billing.api.CycleStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CycleRepository extends JpaRepository<Cycle, UUID> {

    /** Only the owner's id, with NO entity loaded, so the student can be locked before the cycle is read. */
    @Query("select c.studentId from Cycle c where c.id = :id")
    Optional<UUID> findStudentIdById(@Param("id") UUID id);

    Optional<Cycle> findFirstByStudentIdOrderByStartDateDescCreatedAtDesc(UUID studentId);

    Optional<Cycle> findFirstByStudentIdAndStatus(UUID studentId, CycleStatus status);

    List<Cycle> findByStudentIdOrderByStartDateDescCreatedAtDesc(UUID studentId);

    List<Cycle> findByStatus(CycleStatus status);
}
