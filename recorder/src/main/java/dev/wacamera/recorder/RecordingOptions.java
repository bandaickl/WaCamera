package dev.wacamera.recorder;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Pixels are tightly packed, top-down RGBA8. Duration and capture timestamps use real time.
 * audioHelper is an ignored legacy parameter, retained for source compatibility.
 */
public record RecordingOptions(Path ffmpeg, Path outputDirectory, int width, int height,
                               int fps, double durationSeconds, String encoder,
                               Path audioHelper, boolean recordAudio, double playbackSpeed) {
    /** Compatibility constructor for normal-speed recordings. */
    public RecordingOptions(Path ffmpeg, Path outputDirectory, int width, int height,
                            int fps, double durationSeconds, String encoder, Path audioHelper, boolean recordAudio) {
        this(ffmpeg, outputDirectory, width, height, fps, durationSeconds, encoder, audioHelper, recordAudio, 1.0);
    }

    public RecordingOptions {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        if (width < 16 || height < 16 || width > 1920 || height > 1080
                || (width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("Recording dimensions must be even and within 16x16 to 1920x1080");
        }
        if (fps < 1 || fps > 60) throw new IllegalArgumentException("fps must be between 1 and 60");
        if (!Double.isFinite(durationSeconds) || durationSeconds <= 0 || durationSeconds > 3600) {
            throw new IllegalArgumentException("durationSeconds must be positive and at most 3600");
        }
        if (!Double.isFinite(playbackSpeed) || playbackSpeed < 1 || playbackSpeed > 120) {
            throw new IllegalArgumentException("playbackSpeed must be finite and between 1 and 120");
        }
        // Timelapse never loads native audio APIs or opens a device, even if the client keeps audio enabled.
        if (playbackSpeed > 1) recordAudio = false;
        if (encoder == null || encoder.isBlank()) encoder = "libx264";
        if (!encoder.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Invalid encoder name");
    }

    public int frameBytes() { return Math.multiplyExact(Math.multiplyExact(width, height), 4); }
    public long maximumFrames() { return (long) Math.ceil(durationSeconds * fps / playbackSpeed); }
    public double captureFps() { return fps / playbackSpeed; }
}
