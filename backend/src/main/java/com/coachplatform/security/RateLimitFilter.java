package com.coachplatform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Throttles repeated FAILED requests per client IP on the unauthenticated, guessable endpoints
 * (login and invitation preview/accept). Successful requests are not counted.
 * Behind a reverse proxy set server.forward-headers-strategy=native so the real client IP is used.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final Map<String, AttemptLimiter> limitersByPath;

    public RateLimitFilter(Map<String, AttemptLimiter> limitersByPath) {
        this.limitersByPath = limitersByPath;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !limitersByPath.containsKey(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AttemptLimiter limiter = limitersByPath.get(request.getRequestURI());
        // One limiter instance per endpoint group (login vs invitations), so the client IP alone is the key.
        String key = request.getRemoteAddr();
        if (limiter.isBlocked(key)) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(limiter.retryAfter(key).toSeconds()));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"TOO_MANY_ATTEMPTS\"}");
            return;
        }
        chain.doFilter(request, response);
        int status = response.getStatus();
        if (status >= 400 && status < 500 && status != 429) {
            limiter.recordFailure(key);
        }
    }
}
