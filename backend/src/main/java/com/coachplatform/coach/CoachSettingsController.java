package com.coachplatform.coach;

import com.coachplatform.coach.api.CoachSettingsView;
import com.coachplatform.coach.api.UpdateCoachSettings;
import com.coachplatform.security.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The coach's own settings; the coach comes from the token, never from a parameter. */
@RestController
@RequestMapping("/api/coach/settings")
class CoachSettingsController {

    private final CoachService coaches;

    CoachSettingsController(CoachService coaches) {
        this.coaches = coaches;
    }

    @GetMapping
    CoachSettingsView get(@AuthenticationPrincipal AuthPrincipal me) {
        return coaches.settings(me.coachId());
    }

    @PutMapping
    CoachSettingsView update(@AuthenticationPrincipal AuthPrincipal me, @Valid @RequestBody UpdateCoachSettings cmd) {
        return coaches.updateSettings(me.coachId(), cmd);
    }
}
