package com.coachplatform.coach;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CoachSettingsRepository extends JpaRepository<CoachSettings, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CoachSettings s where s.coachId = :id")
    Optional<CoachSettings> findForUpdate(@Param("id") UUID id);
}
