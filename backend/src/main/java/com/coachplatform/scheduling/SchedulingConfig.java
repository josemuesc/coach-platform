package com.coachplatform.scheduling;

import com.coachplatform.scheduling.domain.AttendanceRules;
import com.coachplatform.scheduling.domain.ConfirmationRules;
import com.coachplatform.scheduling.domain.QrTokenRules;
import com.coachplatform.security.AttemptLimiter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import com.coachplatform.scheduling.domain.BlockRules;
import com.coachplatform.scheduling.domain.BookingRules;
import com.coachplatform.scheduling.domain.CancellationPolicy;
import com.coachplatform.scheduling.domain.EventRules;
import com.coachplatform.scheduling.domain.SlotCalendar;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the Spring-free domain. The business day is always America/Bogota. */
@Configuration
class SchedulingConfig {

    static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    @Bean
    SlotCalendar slotCalendar() {
        return new SlotCalendar(BOGOTA);
    }

    @Bean
    BookingRules bookingRules(Clock clock, SlotCalendar slots) {
        return new BookingRules(clock, slots);
    }

    @Bean
    BlockRules blockRules(Clock clock) {
        return new BlockRules(clock, BOGOTA);
    }

    @Bean
    CancellationPolicy cancellationPolicy(Clock clock) {
        return new CancellationPolicy(clock);
    }

    @Bean
    AttendanceRules attendanceRules(Clock clock) {
        return new AttendanceRules(clock);
    }

    @Bean
    EventRules eventRules(Clock clock) {
        return new EventRules(clock);
    }

    @Bean
    ConfirmationRules confirmationRules(Clock clock) {
        return new ConfirmationRules(clock);
    }

    /** The secret signs the rotating event code; QrTokenRules refuses anything under 32 bytes, so the application will not start with a weak one. */
    @Bean
    QrTokenRules qrTokenRules(Clock clock, @Value("${app.qr.secret}") String secret) {
        return new QrTokenRules(clock, secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Failed scans per student (an unknown, stale or foreign code). The per-IP limit lives in the RateLimitFilter. */
    @Bean
    @Qualifier("qrScanUserLimiter")
    AttemptLimiter qrScanUserLimiter(Clock clock,
                                     @Value("${app.security.qr-scan-user.max-failures:10}") int max,
                                     @Value("${app.security.qr-scan-user.window-minutes:10}") long windowMinutes) {
        return new AttemptLimiter(clock, max, Duration.ofMinutes(windowMinutes));
    }
}
