package dev.wacamera.core;

import java.util.Objects;
import java.util.function.LongSupplier;

/** A monotonic nanosecond clock; game tick rate never changes recording duration. */
public final class RecordingClock {
    private final LongSupplier nanoTime;
    private long origin;
    private long lastElapsed;
    private boolean started;

    public RecordingClock() { this(System::nanoTime); }
    public RecordingClock(LongSupplier nanoTime) { this.nanoTime = Objects.requireNonNull(nanoTime); }

    public void start() {
        origin = nanoTime.getAsLong();
        lastElapsed = 0;
        started = true;
    }

    public double elapsedSeconds() { return elapsedNanos() / 1_000_000_000.0; }

    public long elapsedNanos() {
        if (!started) return 0;
        long delta = nanoTime.getAsLong() - origin;
        lastElapsed = Math.max(lastElapsed, delta);
        return lastElapsed;
    }

    /** Number of constant-frame-rate slots covering [0, durationSeconds). */
    public static long frameCount(double durationSeconds, int fps) {
        if (!Double.isFinite(durationSeconds) || durationSeconds < 0 || fps <= 0) {
            throw new IllegalArgumentException("Duration must be finite/nonnegative and fps positive");
        }
        double count = Math.ceil(durationSeconds * fps);
        if (!Double.isFinite(count) || count >= Long.MAX_VALUE) {
            throw new IllegalArgumentException("Frame count exceeds the supported range");
        }
        return (long) count;
    }
}
