package com.coachplatform.students;

import com.coachplatform.common.ValidPassword;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public: the person who received the link from the coach chooses the new password. Nobody is logged in here. */
@RestController
@RequestMapping("/api/auth")
class PasswordResetController {

    private final PasswordResetService resets;

    PasswordResetController(PasswordResetService resets) {
        this.resets = resets;
    }

    record ResetPasswordRequest(@NotBlank @Size(max = 100) String token, @ValidPassword String newPassword) {
        @Override
        public String toString() {
            return "ResetPasswordRequest[token=<redacted>, newPassword=<redacted>]";
        }
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(@Valid @RequestBody ResetPasswordRequest req) {
        resets.redeem(req.token(), req.newPassword());
    }
}
