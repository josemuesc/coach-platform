package com.coachplatform.billing;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CycleExtensionRepository extends JpaRepository<CycleExtensionRecord, UUID> {
}
