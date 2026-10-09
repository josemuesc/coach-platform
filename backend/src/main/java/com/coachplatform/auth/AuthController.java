package com.coachplatform.auth;

import com.coachplatform.auth.AuthDtos.AuthResponse;
import com.coachplatform.auth.AuthDtos.ChangePasswordRequest;
import com.coachplatform.auth.AuthDtos.LoginRequest;
import com.coachplatform.auth.AuthDtos.MeResponse;
import com.coachplatform.auth.AuthDtos.RegisterCoachRequest;
import com.coachplatform.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import com.coachplatform.common.ApiException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService auth;
    private final boolean registrationOpen;

    public AuthController(AuthService auth, @Value("${app.registration.open:false}") boolean registrationOpen) {
        this.auth = auth;
        this.registrationOpen = registrationOpen;
    }

    @PostMapping("/auth/register-coach")
    @ResponseStatus(HttpStatus.CREATED)
    AuthResponse registerCoach(@Valid @RequestBody RegisterCoachRequest req) {
        if (!registrationOpen) {
            // checked before anything touches the database: the answer is the same for every e-mail, so it reveals nothing
            throw new ApiException(HttpStatus.FORBIDDEN, "REGISTRATION_CLOSED");
        }
        return auth.registerCoach(req);
    }

    @PostMapping("/auth/login")
    AuthResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        return auth.login(req, http.getRemoteAddr());
    }

    @PostMapping("/auth/change-password")
    AuthResponse changePassword(@AuthenticationPrincipal AuthPrincipal me, @Valid @RequestBody ChangePasswordRequest req) {
        return auth.changePassword(me.userId(), req);
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal AuthPrincipal me) {
        return auth.me(me.userId(), me.coachId(), me.role());
    }
}
