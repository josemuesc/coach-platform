package com.coachplatform.auth;

import com.coachplatform.auth.AuthDtos.AuthResponse;
import com.coachplatform.auth.AuthDtos.ChangePasswordRequest;
import com.coachplatform.auth.AuthDtos.LoginRequest;
import com.coachplatform.auth.AuthDtos.RegisterCoachRequest;
import com.coachplatform.coach.CoachService;
import com.coachplatform.security.JwtService;
import com.coachplatform.security.UserRole;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final CoachService coaches;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthService(CoachService coaches, AppUserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.coaches = coaches;
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    @Transactional
    public AuthResponse registerCoach(RegisterCoachRequest req) {
        String email = req.email().trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyUsedException();
        }
        UUID coachId = coaches.createCoach(req.name().trim());
        AppUser user = users.save(new AppUser(coachId, email, encoder.encode(req.password()), UserRole.COACH));
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        AppUser user = users.findByEmailIgnoreCase(req.email().trim())
                .filter(AppUser::isActive)
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        return toResponse(user);
    }

    @Transactional
    public AuthResponse changePassword(UUID userId, ChangePasswordRequest req) {
        AppUser user = users.findById(userId).orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        if (!encoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }
        user.changePassword(encoder.encode(req.newPassword()));
        return toResponse(user);
    }

    private AuthResponse toResponse(AppUser user) {
        return new AuthResponse(jwt.issue(user.getId(), user.getCoachId(), user.getRole()), user.getRole().name(), user.getCoachId());
    }
}
