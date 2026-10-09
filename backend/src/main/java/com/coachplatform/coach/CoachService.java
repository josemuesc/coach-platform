package com.coachplatform.coach;

import com.coachplatform.coach.api.BillingSettings;
import com.coachplatform.coach.api.BrandView;
import com.coachplatform.coach.api.CoachSettingsView;
import com.coachplatform.coach.api.SchedulingSettings;
import com.coachplatform.coach.api.UpdateCoachSettings;
import com.coachplatform.coach.domain.GymConsentRules;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Public API of the coach module. Returns IDs/records only, never entities. */
@Service
public class CoachService {

    private final CoachRepository coaches;
    private final CoachSettingsRepository settings;
    private final Clock clock;
    private final GymConsentRules gymConsent = new GymConsentRules();

    public CoachService(CoachRepository coaches, CoachSettingsRepository settings, Clock clock) {
        this.coaches = coaches;
        this.settings = settings;
        this.clock = clock;
    }

    /** Creates a coach (tenant) with default settings and returns its id. */
    @Transactional
    public UUID createCoach(String name) {
        Coach coach = coaches.save(new Coach(name, name));
        settings.save(new CoachSettings(coach.getId()));
        return coach.getId();
    }

    @Transactional(readOnly = true)
    public BrandView brand(UUID coachId) {
        Coach c = coaches.findById(coachId).orElseThrow();
        return new BrandView(c.getBrandName(), c.getPrimaryColor());
    }

    /** The color is stored upper case so the same color is always the same string. */
    @Transactional
    public BrandView updateBrand(UUID coachId, String brandName, String primaryColor) {
        Coach c = coaches.findById(coachId).orElseThrow();
        c.updateBrand(brandName, primaryColor.toUpperCase(java.util.Locale.ROOT));
        return new BrandView(c.getBrandName(), c.getPrimaryColor());
    }

    @Transactional(readOnly = true)
    public BillingSettings billingSettings(UUID coachId) {
        CoachSettings s = settings.findById(coachId).orElseThrow();
        return new BillingSettings(s.getExpiringSoonDays(), s.getExpiringSoonClasses(), s.getMaxExtensionDays());
    }

    @Transactional(readOnly = true)
    public SchedulingSettings schedulingSettings(UUID coachId) {
        CoachSettings s = settings.findById(coachId).orElseThrow();
        return new SchedulingSettings(s.getCancelWindowHours(), s.getClassDurationMinutes(), s.getDefaultGroupCapacity(),
                s.getConfirmationWindowHours(), s.getQrOpenMinutesBefore(), s.getQrCloseHoursAfterEnd());
    }

    @Transactional(readOnly = true)
    public CoachSettingsView settings(UUID coachId) {
        return view(settings.findById(coachId).orElseThrow());
    }

    @Transactional
    public CoachSettingsView updateSettings(UUID coachId, UpdateCoachSettings cmd) {
        CoachSettings s = settings.findById(coachId).orElseThrow();
        var gym = gymConsent.apply(new GymConsentRules.State(s.isGymConsentConfirmed(), s.getGymConsentConfirmedAt()),
                cmd.gymConsentConfirmed(), clock.instant());
        s.update(cmd.cancelWindowHours(), cmd.classDurationMinutes(), cmd.expiringSoonDays(), cmd.expiringSoonClasses(),
                cmd.maxExtensionDays(), cmd.defaultGroupCapacity(), cmd.confirmationWindowHours(), cmd.qrOpenMinutesBefore(),
                cmd.qrCloseHoursAfterEnd(), gym.confirmed(), gym.confirmedAt());
        return view(s);
    }

    private static CoachSettingsView view(CoachSettings s) {
        return new CoachSettingsView(s.getCancelWindowHours(), s.getClassDurationMinutes(), s.getExpiringSoonDays(),
                s.getExpiringSoonClasses(), s.getMaxExtensionDays(), s.getDefaultGroupCapacity(), s.getConfirmationWindowHours(),
                s.getQrOpenMinutesBefore(), s.getQrCloseHoursAfterEnd(), s.isGymConsentConfirmed(), s.getGymConsentConfirmedAt());
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
