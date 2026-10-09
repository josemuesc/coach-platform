package com.coachplatform.security;

import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    public static final String LOGIN_PATH = "/api/auth/login";
    static final String CONFIRM_QR_PATH = "/api/student/attendances/confirm-qr";
    public static final String INVITATION_PREVIEW_PATH = "/api/invitations/preview";
    public static final String INVITATION_ACCEPT_PATH = "/api/invitations/accept";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter, RateLimitFilter rateLimitFilter,
                                    @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsSource))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(a -> a
                        // The container re-dispatches sendError() responses (403, 400, 404...) to /error WITHOUT the JWT; without
                        // this they would all be turned into a misleading 401. A direct call to /error still needs a token.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/auth/register-coach", LOGIN_PATH,
                                INVITATION_PREVIEW_PATH, INVITATION_ACCEPT_PATH).permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // Open to the filter chain on purpose: springdoc registers these paths only with app.openapi.enabled=true,
                        // so in any other profile an anonymous call gets a plain 404 (nothing there), not a 401 that hints at them.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers("/api/coach/**").hasRole("COACH")
                        .requestMatchers("/api/student/**").hasRole("STUDENT")
                        .anyRequest().authenticated())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Only the frontend's own origin (APP_FRONTEND_URL, scheme + host + port; any path is dropped) may call the API from a browser.
     * Authentication is a bearer token in a header, so credentials/cookies are NOT allowed and no wildcard is ever used.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.frontend-url}") String frontendUrl) {
        java.net.URI uri = java.net.URI.create(frontendUrl.trim());
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(origin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(false);
        config.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Authentication is JWT only. Declaring this explicitly stops Spring Boot from creating a generated in-memory user. */
    @Bean
    UserDetailsService noInMemoryUsers() {
        return username -> {
            throw new UsernameNotFoundException("There are no in-memory users");
        };
    }

    // ---- brute-force protection (failures only, in memory) -----------------------------------

    @Bean
    @Qualifier("loginIpLimiter")
    AttemptLimiter loginIpLimiter(Clock clock,
                                  @Value("${app.security.login-ip.max-failures:30}") int max,
                                  @Value("${app.security.window-minutes:15}") long windowMinutes) {
        return new AttemptLimiter(clock, max, Duration.ofMinutes(windowMinutes));
    }

    @Bean
    @Qualifier("invitationIpLimiter")
    AttemptLimiter invitationIpLimiter(Clock clock,
                                       @Value("${app.security.invitation-ip.max-failures:10}") int max,
                                       @Value("${app.security.window-minutes:15}") long windowMinutes) {
        return new AttemptLimiter(clock, max, Duration.ofMinutes(windowMinutes));
    }

    @Bean
    @Qualifier("loginEmailLimiter")
    AttemptLimiter loginEmailLimiter(Clock clock,
                                     @Value("${app.security.login-email.max-failures:5}") int max,
                                     @Value("${app.security.window-minutes:15}") long windowMinutes) {
        return new AttemptLimiter(clock, max, Duration.ofMinutes(windowMinutes));
    }

    @Bean
    @Qualifier("qrScanIpLimiter")
    AttemptLimiter qrScanIpLimiter(Clock clock,
                                   @Value("${app.security.qr-scan-ip.max-failures:30}") int max,
                                   @Value("${app.security.window-minutes:15}") long windowMinutes) {
        return new AttemptLimiter(clock, max, Duration.ofMinutes(windowMinutes));
    }

    @Bean
    RateLimitFilter rateLimitFilter(@Qualifier("loginIpLimiter") AttemptLimiter loginIp,
                                    @Qualifier("invitationIpLimiter") AttemptLimiter invitationIp,
                                    @Qualifier("qrScanIpLimiter") AttemptLimiter qrScanIp) {
        return new RateLimitFilter(Map.of(
                LOGIN_PATH, loginIp,
                INVITATION_PREVIEW_PATH, invitationIp,
                INVITATION_ACCEPT_PATH, invitationIp,
                CONFIRM_QR_PATH, qrScanIp));
    }

    /** The filters belong to the security chain only; stop Spring Boot from also registering them in the servlet container. */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter filter) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
