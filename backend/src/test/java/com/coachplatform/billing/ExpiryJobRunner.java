package com.coachplatform.billing;

import org.springframework.context.ApplicationContext;

/** Test-only door to the package-private daily job, so tests in other packages can run it by hand. */
public final class ExpiryJobRunner {

    private ExpiryJobRunner() {
    }

    public static void runOnce(ApplicationContext context) {
        context.getBean(CycleExpiryJob.class).run();
    }
}
