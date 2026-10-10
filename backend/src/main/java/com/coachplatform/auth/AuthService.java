package com.coachplatform.auth;

import com.coachplatform.auth.AuthDtos.AuthResponse;
import com.coachplatform.auth.AuthDtos.ChangePasswordRequest;
import com.coachplatform.auth.AuthDtos.LoginRequest;
import com.coachplatform.auth.AuthDtos.MeResponse;
import com.coachplatform.auth.AuthDtos.RegisterCoachRequest;
import com.coachplatform.auth.api.AccountEvent;
import com.coachplatform.auth.api.PasswordChangeMethod;
import com.coachplatform.coach.CoachService;
import com.coachplatform.common.ApiException;
import com.coachplatform.security.AttemptLimiter;
import com.coachplatform.security.JwtService;
import com.coachplatform.security.UserRole;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
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
    private final AccountAuditService accountAudit;
    private final Clock clock;
    private final AttemptLimiter loginEmailLimiter;
    private final AttemptLimiter loginEmailIpLimiter;
    private final AttemptLimiter changePasswordLimiter;
    /** A real BCrypt hash of a throwaway value: login compares against it when the account is unknown, so every login costs the same. */
    private final String timingHash;

    public AuthService(CoachService coaches, AppUserRepository users, PasswordEncoder encoder, JwtService jwt,
                       AccountAuditService accountAudit, Clock clock,
                       @Qualifier("loginEmailLimiter") AttemptLimiter loginEmailLimiter,
                       @Qualifier("loginEmailIpLimiter") AttemptLimiter loginEmailIpLimiter,
                       @Qualifier("changePasswordLimiter") AttemptLimiter changePasswordLimiter) {
        this.coaches = coaches;
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.accountAudit = accountAudit;
        this.clock = clock;
        this.loginEmailLimiter = loginEmailLimiter;
        this.loginEmailIpLimiter = loginEmailIpLimiter;
        this.changePasswordLimiter = changePasswordLimiter;
        this.timingHash = encoder.encode(UUID.randomUUID().toString());
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
     * Failed attempts are counted per email+IP (a stranger cannot lock somebody out from another address), per email as a global
     * ceiling per hour (distributed guessing), and per IP in the RateLimitFilter. Unknown emails are counted exactly like known
     * ones. One BCrypt comparison ALWAYS runs - against a throwaway hash when the account is unknown - and unknown email, wrong
     * password and suspended account all end in the same 401, so neither the answer nor its timing tells them apart.
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req, String ip) {
        String email = req.email().trim().toLowerCase();
        String pair = email + "|" + ip;
        if (loginEmailLimiter.isBlocked(email) || loginEmailIpLimiter.isBlocked(pair)) {
            throw new TooManyAttemptsException();
        }
        AppUser user = users.findByEmailIgnoreCase(email).orElse(null);
        boolean passwordOk = safeMatches(req.password(), user == null ? timingHash : user.getPasswordHash());
        if (user == null || !user.isActive() || !passwordOk) {
            loginEmailLimiter.recordFailure(email);
            loginEmailIpLimiter.recordFailure(pair);
            throw new BadCredentialsException("Invalid credentials");
        }
        loginEmailIpLimiter.reset(pair);
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

    /** 403 ACCOUNT_SUSPENDED unless the login exists and is active. */
    @Transactional(readOnly = true)
    public void requireActiveAccount(UUID userId) {
        if (users.findById(userId).filter(AppUser::isActive).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED");
        }
    }

    /**
     * The user changes their own password knowing the current one. Returns a NEW token: every earlier one (this user's other
     * sessions, or a stolen one) stops working. Wrong current passwords are limited per user.
     */
    @Transactional
    public AuthResponse changePassword(UUID userId, ChangePasswordRequest req) {
        String key = userId.toString();
        if (changePasswordLimiter.isBlocked(key)) {
            throw new TooManyAttemptsException();
        }
        AppUser user = users.findById(userId).orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        if (!safeMatches(req.currentPassword(), user.getPasswordHash())) {
            changePasswordLimiter.recordFailure(key);
            throw new BadCredentialsException("Invalid credentials");
        }
        changePasswordLimiter.reset(key);
        user.changePassword(encoder.encode(req.newPassword()), PasswordChangeMethod.SELF, clock.instant());
        accountAudit.record(AccountEvent.PASSWORD_CHANGED, userId, userId, null, null);
        return toResponse(user);
    }

    /**
     * Sets the password through a one-time reset link (called by the students module, in the transaction that consumes the
     * link). Ends every earlier session, forgets the failed-login counters of the account (the person usually got locked out
     * first) and records how the password changed. The caller writes the RESET_LINK_USED audit line.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void applyPasswordReset(UUID userId, String newPassword) {
        AppUser user = users.findById(userId).filter(AppUser::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RESET_LINK"));
        user.changePassword(encoder.encode(newPassword), PasswordChangeMethod.COACH_LINK, clock.instant());
        users.saveAndFlush(user);
        loginEmailLimiter.reset(user.getEmail());
        loginEmailIpLimiter.resetWithPrefix(user.getEmail() + "|");
        changePasswordLimiter.reset(userId.toString());
    }

    @Transactional(readOnly = true)
    public MeResponse me(UUID userId, UUID coachId, UserRole role) {
        AppUser user = users.findById(userId).orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        var brand = coaches.brand(coachId);
        return new MeResponse(userId, coachId, role.name(), brand.brandName(), brand.primaryColor(), user.getPasswordChangedAt(),
                user.getPasswordChangeMethod());
    }

    /** When and how the login's password last changed (for the coach's view of a student's account). */
    @Transactional(readOnly = true)
    public com.coachplatform.auth.api.PasswordInfo passwordInfo(UUID userId) {
        AppUser user = users.findById(userId).orElseThrow();
        return new com.coachplatform.auth.api.PasswordInfo(user.getPasswordChangedAt(), user.getPasswordChangeMethod());
    }

    /** True while a token issued under {@code epoch} is still current for the user (a deleted user is never current). */
    @Transactional(readOnly = true)
    public boolean sessionIsCurrent(UUID userId, long epoch) {
        return users.findById(userId).map(u -> u.passwordEpoch() == epoch).orElse(false);
    }

    private boolean safeMatches(String raw, String hash) {
        try {
            return encoder.matches(raw, hash);
        } catch (IllegalArgumentException e) {
            return false;   // e.g. a password BCrypt refuses: it simply cannot match
        }
    }

    private AuthResponse toResponse(AppUser user) {
        return new AuthResponse(jwt.issue(user.getId(), user.getCoachId(), user.getRole(), user.passwordEpoch()), user.getRole().name(),
                user.getCoachId());
    }
}
