package com.coachplatform.students;

import com.coachplatform.students.api.InvitationAccepted;
import com.coachplatform.students.api.InvitationPreview;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public endpoints. The token travels in the JSON body (never in the URL) so it cannot end up in access logs. */
@RestController
@RequestMapping("/api/invitations")
class InvitationController {

    private final InvitationService invitations;

    InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    record TokenRequest(@NotBlank @Size(max = 100) String token) {
        @Override
        public String toString() {
            return "TokenRequest[token=<redacted>]";
        }
    }

    record AcceptRequest(@NotBlank @Size(max = 100) String token, @NotBlank @Size(min = 10, max = 72) String password) {
        @Override
        public String toString() {
            return "AcceptRequest[token=<redacted>, password=<redacted>]";
        }
    }

    @PostMapping("/preview")
    InvitationPreview preview(@Valid @RequestBody TokenRequest req) {
        return invitations.preview(req.token());
    }

    @PostMapping("/accept")
    InvitationAccepted accept(@Valid @RequestBody AcceptRequest req) {
        return invitations.accept(req.token(), req.password());
    }
}
