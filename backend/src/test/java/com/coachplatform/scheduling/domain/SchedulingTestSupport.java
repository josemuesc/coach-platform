package com.coachplatform.scheduling.domain;

import com.coachplatform.scheduling.domain.SchedulingRuleException.Code;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

final class SchedulingTestSupport {

    static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    private SchedulingTestSupport() {
    }

    /** A clock fixed at a local wall-clock time in Bogota. */
    static Clock clockAt(String localDateTime) {
        return Clock.fixed(local(localDateTime), ZoneOffset.UTC);
    }

    /** "2026-10-12T14:00" read as Bogota time -> the matching instant. */
    static Instant local(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(BOGOTA).toInstant();
    }

    static Code codeOf(Runnable action) {
        try {
            action.run();
        } catch (SchedulingRuleException e) {
            return e.code();
        }
        throw new AssertionError("expected a SchedulingRuleException");
    }
}
