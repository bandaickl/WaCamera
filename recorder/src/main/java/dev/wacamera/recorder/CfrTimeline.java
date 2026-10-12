package dev.wacamera.recorder;

/** Constant frame rate arithmetic, independent of Minecraft and wall-clock origin. */
public final class CfrTimeline {
    private final int fps;
    private final double playbackSpeed;
    private final double framesPerNano;

    public CfrTimeline(int fps) {
        this(fps, 1.0);
    }

    /** Input times stay in real nanoseconds; output frames retain the requested playback fps. */
    public CfrTimeline(int fps, double playbackSpeed) {
        if (fps < 1 || fps > 240) throw new IllegalArgumentException("Invalid fps");
        if (!Double.isFinite(playbackSpeed) || playbackSpeed < 1 || playbackSpeed > 120) {
            throw new IllegalArgumentException("Invalid playback speed");
        }
        this.fps = fps;
        this.playbackSpeed = playbackSpeed;
        this.framesPerNano = fps / (playbackSpeed * 1_000_000_000.0);
    }

    public long nearestSlot(long elapsedNanos) {
        return Math.max(0, Math.round(elapsedNanos * framesPerNano));
    }

    public long framesForDuration(long elapsedNanos) {
        if (elapsedNanos <= 0) return 0;
        return (long) Math.ceil(elapsedNanos * framesPerNano);
    }

    public long readyFrames(long elapsedNanos, long settleNanos) {
        if (elapsedNanos < settleNanos) return 0;
        return (long) Math.floor((elapsedNanos - settleNanos) * framesPerNano) + 1;
    }

    public double secondsForFrames(long frameCount) { return (double) frameCount / fps; }
    public double captureSecondsForFrames(long frameCount) { return frameCount * playbackSpeed / fps; }
}
