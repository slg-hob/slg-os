package de.systemlogistics.slgos.core.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock that tests move forward; timeouts are tested with it, never with sleeps (PRD-0003 section 9). */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    public MutableClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    public static MutableClock atNoon() {
        return new MutableClock(Instant.parse("2026-10-20T12:00:00Z"));
    }

    public void advance(Duration duration) {
        now.updateAndGet(t -> t.plus(duration));
    }

    public void set(Instant instant) {
        now.set(instant);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
