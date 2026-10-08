package com.coachplatform.scheduling;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AvailabilityBlockRepository extends JpaRepository<AvailabilityBlock, UUID> {

    @Query("select b from AvailabilityBlock b where b.startsAt < :to and b.endsAt > :from order by b.startsAt")
    List<AvailabilityBlock> findOverlapping(@Param("from") Instant from, @Param("to") Instant to);
}
