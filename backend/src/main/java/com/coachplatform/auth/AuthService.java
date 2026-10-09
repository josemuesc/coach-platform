package com.coachplatform.auth;

import com.coachplatform.auth.AuthDtos.AuthResponse;
import com.coachplatform.auth.AuthDtos.ChangePasswordRequest;
import com.coachplatform.auth.AuthDtos.LoginRequest;
import com.coachplatform.auth.AuthDtos.RegisterCoachRequest;
import com.coachplatform.coach.CoachService;
import com.coachplatform.security.AttemptLimiter;
import com.coachplatform.security.JwtService;
import com.coachplatform.security.UserRole;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final AttemptLimiter loginEmailLimiter;

    public AuthService(CoachService coaches, AppUserRepository users, PasswordEncoder encoder, JwtService jwt,
                       @Qualifier("loginEmailLimiter") AttemptLimiter loginEmailLimiter) {
        this.loginEmailLimiter = loginEmailLimiter;
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

    /**
     * Failed attempts are counted per email (also for unknown emails, so existence is not revealed) and a success
     * resets the counter. The per-IP limit lives in the RateLimitFilter.
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        String email = req.email().trim().toLowerCase();
        if (loginEmailLimiter.isBlocked(email)) {
            throw new TooManyAttemptsException();
        }
        AppUser user = users.findByEmailIgnoreCase(email)
                .filter(AppUser::isActive)
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
                .orElse(null);
        if (user == null) {
            loginEmailLimiter.recordFailure(email);
            throw new BadCredentialsException("Invalid credentials");
        }
        loginEmailLimiter.reset(email);
        return toResponse(user);
    }

    /** Creates the login of a student who accepted an invitation, with the password the student chose. */
    @Transactional
    public UUID createStudentAccount(UUID coachId, String email, String rawPassword) {
        String normalized = email.trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(normalized)) {
            throw new EmailAlreadyUsedException();
        }
        return users.save(new AppUser(coachId, normalized, encoder.encode(rawPassword), UserRole.STUDENT)).getId();
    }

    /** Suspends a login (it can no longer sign in). Used when a data consent is revoked. Idempotent. */
    @Transactional
    public void deactivateAccount(UUID userId) {
        users.findById(userId).ifPresent(AppUser::deactivate);
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
