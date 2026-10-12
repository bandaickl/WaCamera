package dev.wacamera.core;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** The portable, versioned shot format. Environment/encoder settings belong elsewhere. */
public record ShotDefinition(int schemaVersion, String name, String dimension, double durationSeconds,
                             int countdownSeconds, String positionInterpolation, String speedMode,
                             String lookMode, float fovDegrees, List<ShotPoint> points, Vec3d target,
                             double playbackSpeed) {
    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final int MAX_POINTS = 64;
    private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    public ShotDefinition {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported shot schema version: " + schemaVersion);
        }
        if (name == null || name.isBlank() || name.length() > 80) {
            throw new IllegalArgumentException("Shot name must contain 1 to 80 characters");
        }
        if (dimension == null || dimension.length() > 256 || !DIMENSION.matcher(dimension).matches()) {
            throw new IllegalArgumentException("Dimension must be a namespaced resource id");
        }
        if (!Double.isFinite(durationSeconds) || durationSeconds < 1 || durationSeconds > 3600) {
            throw new IllegalArgumentException("Duration must be between 1 and 3600 seconds");
        }
        if (!Double.isFinite(playbackSpeed) || playbackSpeed < 1 || playbackSpeed > 120) {
            throw new IllegalArgumentException("Playback speed must be between 1 and 120");
        }
        if (countdownSeconds < 0 || countdownSeconds > 10) {
            throw new IllegalArgumentException("Countdown must be between 0 and 10 seconds");
        }
        if (!Set.of("linear", "smooth").contains(positionInterpolation == null ? "" : positionInterpolation)) {
            throw new IllegalArgumentException("Position interpolation must be linear or smooth");
        }
        if (!"constant".equals(speedMode)) {
            throw new IllegalArgumentException("Only constant speed is supported");
        }
        if (!Set.of("registered", "self", "target").contains(lookMode == null ? "" : lookMode)) {
            throw new IllegalArgumentException("Look mode must be registered, self or target");
        }
        if (!Float.isFinite(fovDegrees) || fovDegrees <= 0 || fovDegrees >= 180) {
            throw new IllegalArgumentException("Field of view must be between 0 and 180 degrees, exclusive");
        }
        if (points == null || points.isEmpty() || points.size() > MAX_POINTS || points.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("A shot requires 1 to 64 non-null points");
        }
        Set<String> ids = new HashSet<>();
        for (ShotPoint point : points) {
            if (!ids.add(point.id())) throw new IllegalArgumentException("Duplicate point id: " + point.id());
        }
        points = List.copyOf(points);
        if ("target".equals(lookMode) && target == null) {
            throw new IllegalArgumentException("Target look mode requires target coordinates");
        }
    }

    /** Source-compatible construction of pre-speed shots. Schema 1 upgrades to the current format. */
    public ShotDefinition(int schemaVersion, String name, String dimension, double durationSeconds,
                          int countdownSeconds, String positionInterpolation, String speedMode,
                          String lookMode, float fovDegrees, List<ShotPoint> points, Vec3d target) {
        this(schemaVersion == 1 ? CURRENT_SCHEMA_VERSION : schemaVersion, name, dimension, durationSeconds,
                countdownSeconds, positionInterpolation, speedMode, lookMode, fovDegrees, points, target, 1);
    }

    public static ShotDefinition defaults(String dimension, List<ShotPoint> points) {
        return new ShotDefinition(CURRENT_SCHEMA_VERSION, "untitled", dimension, 10, 3, "linear", "constant",
                "registered", 70, points, null, 1);
    }
}
