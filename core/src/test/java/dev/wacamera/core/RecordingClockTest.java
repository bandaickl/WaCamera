package dev.wacamera.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RecordingClockTest {
    @Test void stalledRenderingDoesNotShortenElapsedTimeOrFrameCount() {
        AtomicLong nanos = new AtomicLong(1_234);
        RecordingClock clock = new RecordingClock(nanos::get);
        assertEquals(0, clock.elapsedSeconds());
        clock.start();
        for (long elapsed : new long[]{16_666_667, 33_333_333, 2_000_000_000L, 10_000_000_000L}) {
            nanos.set(1_234 + elapsed);
            assertEquals(elapsed / 1_000_000_000.0, clock.elapsedSeconds(), 1e-10);
        }
        assertEquals(300, RecordingClock.frameCount(clock.elapsedSeconds(), 30));
        assertEquals(301, RecordingClock.frameCount(10.01, 30));
    }

    @Test void clockCannotGoBackwardsAndStartResetsIt() {
        AtomicLong nanos = new AtomicLong(100);
        RecordingClock clock = new RecordingClock(nanos::get);
        clock.start();
        nanos.set(300);
        assertEquals(200, clock.elapsedNanos());
        nanos.set(200);
        assertEquals(200, clock.elapsedNanos());
        clock.start();
        assertEquals(0, clock.elapsedNanos());
    }

    @Test void invalidFrameCountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RecordingClock.frameCount(Double.NaN, 30));
        assertThrows(IllegalArgumentException.class, () -> RecordingClock.frameCount(-1, 30));
        assertThrows(IllegalArgumentException.class, () -> RecordingClock.frameCount(1, 0));
        assertEquals(0, RecordingClock.frameCount(0, 30));
    }
}
