package com.coachplatform.auth;

import com.coachplatform.auth.AuthDtos.AuthResponse;
import com.coachplatform.auth.AuthDtos.ChangePasswordRequest;
import com.coachplatform.auth.AuthDtos.LoginRequest;
import com.coachplatform.auth.AuthDtos.MeResponse;
import com.coachplatform.auth.AuthDtos.RegisterCoachRequest;
import com.coachplatform.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/auth/register-coach")
    @ResponseStatus(HttpStatus.CREATED)
    AuthResponse registerCoach(@Valid @RequestBody RegisterCoachRequest req) {
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
