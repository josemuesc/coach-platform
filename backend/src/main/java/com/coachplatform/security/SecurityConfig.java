package com.coachplatform.security;

import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.time.Duration;
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

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    public static final String LOGIN_PATH = "/api/auth/login";
    public static final String INVITATION_PREVIEW_PATH = "/api/invitations/preview";
    public static final String INVITATION_ACCEPT_PATH = "/api/invitations/accept";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter, RateLimitFilter rateLimitFilter)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(a -> a
                        // The container re-dispatches sendError() responses (403, 400, 404...) to /error WITHOUT the JWT; without
                        // this they would all be turned into a misleading 401. A direct call to /error still needs a token.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/auth/register-coach", LOGIN_PATH,
                                INVITATION_PREVIEW_PATH, INVITATION_ACCEPT_PATH).permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/coach/**").hasRole("COACH")
                        .anyRequest().authenticated())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
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
    RateLimitFilter rateLimitFilter(@Qualifier("loginIpLimiter") AttemptLimiter loginIp,
                                    @Qualifier("invitationIpLimiter") AttemptLimiter invitationIp) {
        return new RateLimitFilter(Map.of(
                LOGIN_PATH, loginIp,
                INVITATION_PREVIEW_PATH, invitationIp,
                INVITATION_ACCEPT_PATH, invitationIp));
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
