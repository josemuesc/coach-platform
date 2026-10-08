package com.coachplatform.coach;

import com.coachplatform.coach.api.BillingSettings;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public API of the coach module. Returns IDs/records only, never entities. */
@Service
public class CoachService {

    private final CoachRepository coaches;
    private final CoachSettingsRepository settings;

    public CoachService(CoachRepository coaches, CoachSettingsRepository settings) {
        this.coaches = coaches;
        this.settings = settings;
    }

    /** Creates a coach (tenant) with default settings and returns its id. */
    @Transactional
    public UUID createCoach(String name) {
        Coach coach = coaches.save(new Coach(name, name));
        settings.save(new CoachSettings(coach.getId()));
        return coach.getId();
    }

    @Transactional(readOnly = true)
    public String brandName(UUID coachId) {
        return coaches.findById(coachId).map(Coach::getBrandName).orElseThrow();
    }

    @Transactional(readOnly = true)
    public BillingSettings billingSettings(UUID coachId) {
        CoachSettings s = settings.findById(coachId).orElseThrow();
        return new BillingSettings(s.getExpiringSoonDays(), s.getExpiringSoonClasses(), s.getMaxExtensionDays());
    }
}
