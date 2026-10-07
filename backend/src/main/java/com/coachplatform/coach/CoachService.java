package com.coachplatform.coach;

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
}
