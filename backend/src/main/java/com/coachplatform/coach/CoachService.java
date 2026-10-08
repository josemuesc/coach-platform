package com.coachplatform.coach;

import com.coachplatform.coach.api.BillingSettings;
import com.coachplatform.coach.api.CoachSettingsView;
import com.coachplatform.coach.api.SchedulingSettings;
import com.coachplatform.coach.api.UpdateCoachSettings;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

    @Transactional(readOnly = true)
    public SchedulingSettings schedulingSettings(UUID coachId) {
        CoachSettings s = settings.findById(coachId).orElseThrow();
        return new SchedulingSettings(s.getCancelWindowHours(), s.getClassDurationMinutes());
    }

    @Transactional(readOnly = true)
    public CoachSettingsView settings(UUID coachId) {
        return view(settings.findById(coachId).orElseThrow());
    }

    @Transactional
    public CoachSettingsView updateSettings(UUID coachId, UpdateCoachSettings cmd) {
        CoachSettings s = settings.findById(coachId).orElseThrow();
        s.update(cmd.cancelWindowHours(), cmd.classDurationMinutes(), cmd.expiringSoonDays(), cmd.expiringSoonClasses(),
                cmd.maxExtensionDays());
        return view(s);
    }

    private static CoachSettingsView view(CoachSettings s) {
        return new CoachSettingsView(s.getCancelWindowHours(), s.getClassDurationMinutes(), s.getExpiringSoonDays(),
                s.getExpiringSoonClasses(), s.getMaxExtensionDays());
    }

    /**
     * Serializes everything that creates classes in this coach's calendar (SELECT ... FOR UPDATE on the coach's settings
     * row). Without it, two students racing for overlapping slots insert at the same time and PostgreSQL's exclusion
     * constraint can only resolve it by aborting one of them with a deadlock. GLOBAL LOCK ORDER: the student's row
     * first, then this one. Must run inside the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockCalendar(UUID coachId) {
        settings.findForUpdate(coachId).orElseThrow();
    }
}
