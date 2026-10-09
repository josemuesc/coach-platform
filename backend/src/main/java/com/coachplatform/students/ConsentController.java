package com.coachplatform.students;

import com.coachplatform.security.AuthPrincipal;
import com.coachplatform.students.api.ConsentType;
import com.coachplatform.students.api.StudentConsentsView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consents of a student. The coach side names the student; the student side NEVER accepts a student id (it comes from the
 * token), so a student can only touch their own consents.
 */
@RestController
class ConsentController {

    private final ConsentService consents;

    ConsentController(ConsentService consents) {
        this.consents = consents;
    }

    record AcceptConsentRequest(@NotBlank @Size(max = 30) String version) {
    }

    // ---- coach ----------------------------------------------------------------------------------------------

    @GetMapping("/api/coach/students/{id}/consents")
    StudentConsentsView coachView(@PathVariable UUID id) {
        return consents.forStudent(id);
    }

    @PostMapping("/api/coach/students/{id}/consents/{type}/accept")
    StudentConsentsView coachAccept(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id, @PathVariable ConsentType type,
                                    @Valid @RequestBody AcceptConsentRequest req) {
        return consents.acceptAsCoach(id, type, req.version(), me.userId());
    }

    @PostMapping("/api/coach/students/{id}/consents/{type}/revoke")
    StudentConsentsView coachRevoke(@AuthenticationPrincipal AuthPrincipal me, @PathVariable UUID id, @PathVariable ConsentType type) {
        return consents.revokeAsCoach(id, type, me.userId());
    }

    // ---- student ---------------------------------------------------------------------------------------------

    @GetMapping("/api/student/consents")
    StudentConsentsView mine(@AuthenticationPrincipal AuthPrincipal me) {
        return consents.mine(me.userId());
    }

    @PostMapping("/api/student/consents/{type}/accept")
    StudentConsentsView accept(@AuthenticationPrincipal AuthPrincipal me, @PathVariable ConsentType type,
                               @Valid @RequestBody AcceptConsentRequest req) {
        return consents.acceptAsStudent(me.userId(), type, req.version());
    }

    @PostMapping("/api/student/consents/{type}/revoke")
    StudentConsentsView revoke(@AuthenticationPrincipal AuthPrincipal me, @PathVariable ConsentType type) {
        return consents.revokeAsStudent(me.userId(), type);
    }
}
