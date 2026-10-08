package com.coachplatform.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test clock the test can move: lets HTTP-level tests jump to the deadline day, past expiry, etc. */
public class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public void set(Instant instant) { this.now = instant; }

    public void advance(Duration duration) { this.now = now.plus(duration); }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }

    @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }

    @Override public Instant instant() { return now; }
}
