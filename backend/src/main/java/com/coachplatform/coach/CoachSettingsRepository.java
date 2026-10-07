package com.coachplatform.coach;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoachSettingsRepository extends JpaRepository<CoachSettings, UUID> {
}
