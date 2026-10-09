package com.coachplatform.auth;

import com.coachplatform.common.ApiException;
import com.coachplatform.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * A JWT is only good while the password it was issued under is still the current one. Changing or resetting a password moves the
 * user's epoch, so every earlier token (a stolen one, the other devices) is answered with 401 INVALID_SESSION. Costs one primary-key
 * read per authenticated request. Public endpoints carry no principal and are not touched.
 */
@Component
class SessionFreshnessInterceptor implements HandlerInterceptor {

    private final AuthService auth;

    SessionFreshnessInterceptor(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal p
                && !auth.sessionIsCurrent(p.userId(), p.passwordEpoch())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SESSION");
        }
        return true;
    }
}
