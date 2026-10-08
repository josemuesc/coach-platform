package com.coachplatform.scheduling;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AvailabilityRuleRepository extends JpaRepository<AvailabilityRule, UUID> {

    List<AvailabilityRule> findAllByOrderByDayOfWeekAscStartTimeAsc();
}
