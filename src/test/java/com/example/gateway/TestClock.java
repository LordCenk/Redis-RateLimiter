package com.example.gateway;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

public class TestClock extends Clock {
    private Instant currentInstant;
    private final ZoneId zone;

    public TestClock(Instant initialInstant, ZoneId zone) {
        this.currentInstant = initialInstant;
        this.zone = zone;
    }

    public TestClock(Instant initialInstant) {
        this(initialInstant, ZoneId.of("UTC"));
    }

    public static TestClock now() {
        return new TestClock(Instant.now());
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new TestClock(currentInstant, zone);
    }

    @Override
    public Instant instant() {
        return currentInstant;
    }

    public void advanceSeconds(long seconds) {
        this.currentInstant = this.currentInstant.plusSeconds(seconds);
    }

    public void advanceMillis(long millis) {
        this.currentInstant = this.currentInstant.plusMillis(millis);
    }
}
