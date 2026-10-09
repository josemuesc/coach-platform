package com.coachplatform.coach;

import com.coachplatform.coach.api.BrandView;
import com.coachplatform.coach.api.UpdateBrandRequest;
import com.coachplatform.security.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The coach's own brand (name and color); the coach comes from the token, never from a parameter. */
@RestController
@RequestMapping("/api/coach/brand")
class CoachBrandController {

    private final CoachService coaches;

    CoachBrandController(CoachService coaches) {
        this.coaches = coaches;
    }

    @GetMapping
    BrandView get(@AuthenticationPrincipal AuthPrincipal me) {
        return coaches.brand(me.coachId());
    }

    @PutMapping
    BrandView update(@AuthenticationPrincipal AuthPrincipal me, @Valid @RequestBody UpdateBrandRequest req) {
        return coaches.updateBrand(me.coachId(), req.brandName().trim(), req.primaryColor());
    }
}
