package com.coachplatform.billing;

import com.coachplatform.billing.domain.CycleCalendar;
import com.coachplatform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes overdue cycles. Reads already show the effective state, so this only makes the stored status catch up.
 * Idempotent (acts only on ACTIVE cycles), so a repeated run or a run on every instance is harmless.
 * Runs daily at 00:10 Bogota time and once at startup to recover from downtime.
 */
@Component
class CycleExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(CycleExpiryJob.class);

    private final ExpiredCycleFinder finder;
    private final BillingService billing;
    private final CycleCalendar calendar;
    private final boolean runOnStartup;

    CycleExpiryJob(ExpiredCycleFinder finder, BillingService billing, CycleCalendar calendar,
                   @Value("${app.billing.expiry-job.run-on-startup:true}") boolean runOnStartup) {
        this.finder = finder;
        this.billing = billing;
        this.calendar = calendar;
        this.runOnStartup = runOnStartup;
    }

    @Scheduled(cron = "${app.billing.expiry-job.cron:0 10 0 * * *}", zone = "America/Bogota")
    void run() {
        int closed = 0;
        for (var overdue : finder.findOverdue(calendar.today())) {
            try {
                TenantContext.runAs(overdue.coachId(), () -> billing.closeIfDue(overdue.cycleId()));
                closed++;
            } catch (RuntimeException e) {
                log.warn("Could not close overdue cycle {}: {}", overdue.cycleId(), e.getClass().getSimpleName());
            }
        }
        if (closed > 0) {
            log.info("Closed {} overdue cycle(s)", closed);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void catchUpAtStartup() {
        if (runOnStartup) {
            run();
        }
    }
}
