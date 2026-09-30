package com.clawkit.ops.loop.automation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** Test-only clock for accelerated, deterministic soak scenarios. */
final class MutableTestClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    MutableTestClock(Instant initial, ZoneId zone) {
        this.instant = Objects.requireNonNull(initial, "initial");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    @Override public ZoneId getZone() { return zone; }

    @Override public Clock withZone(ZoneId requestedZone) {
        return new MutableTestClock(instant, requestedZone);
    }

    @Override public synchronized Instant instant() { return instant; }

    synchronized void advance(Duration duration) {
        if (duration.isNegative()) throw new IllegalArgumentException("duration must not be negative");
        instant = instant.plus(duration);
    }
}
