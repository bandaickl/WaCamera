package dev.wacamera.core;

import java.util.ArrayList;
import java.util.UUID;

/** Immutable point edits shared by the client commands. Point numbers are one-based. */
public final class ShotEditor {
    private ShotEditor() { }

    /** Adds the next numbered point without changing shot settings or existing point identities. */
    public static ShotDefinition appendPoint(ShotDefinition shot, double x, double y, double z,
                                             double yaw, double pitch) {
        requireShot(shot);
        return setPoint(shot, shot.points().size() + 1, x, y, z, yaw, pitch);
    }

    /** Replaces an existing point, or appends exactly the next point; gaps are never created. */
    public static ShotDefinition setPoint(ShotDefinition shot, int oneBasedIndex,
                                          double x, double y, double z, double yaw, double pitch) {
        requireShot(shot);
        int count = shot.points().size();
        if (oneBasedIndex < 1 || oneBasedIndex > count + 1) {
            throw new IllegalArgumentException("Point number must select an existing point or the next point (1 to "
                    + Math.min(count + 1, ShotDefinition.MAX_POINTS) + ")");
        }
        boolean append = oneBasedIndex == count + 1;
        if (append && count == ShotDefinition.MAX_POINTS) {
            throw new IllegalArgumentException("A shot can contain at most " + ShotDefinition.MAX_POINTS + " points");
        }
        // Validate doubles before wrapping or narrowing, so NaN, infinity and tiny pitch overflows cannot hide.
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) {
            throw new IllegalArgumentException("Point angles must be finite");
        }
        if (pitch < -90 || pitch > 90) {
            throw new IllegalArgumentException("Pitch must be between -90 and 90 degrees");
        }
        String id = append ? UUID.randomUUID().toString() : shot.points().get(oneBasedIndex - 1).id();
        float normalizedYaw = PathSampler.wrapDegrees(yaw);
        // A value just below +180 can round up when stored in the shot's float field.
        if (normalizedYaw == 180) normalizedYaw = -180;
        ShotPoint point = new ShotPoint(id, x, y, z, normalizedYaw, (float) pitch);
        ArrayList<ShotPoint> points = new ArrayList<>(shot.points());
        if (append) points.add(point);
        else points.set(oneBasedIndex - 1, point);
        return new ShotDefinition(shot.schemaVersion(), shot.name(), shot.dimension(), shot.durationSeconds(),
                shot.countdownSeconds(), shot.positionInterpolation(), shot.speedMode(), shot.lookMode(),
                shot.fovDegrees(), points, shot.target(), shot.playbackSpeed());
    }

    /** Resolves a registered point, for example before sending a permission-checked teleport command. */
    public static ShotPoint pointAt(ShotDefinition shot, int oneBasedIndex) {
        requireShot(shot);
        if (oneBasedIndex < 1 || oneBasedIndex > shot.points().size()) {
            throw new IllegalArgumentException("Point number must be between 1 and " + shot.points().size());
        }
        return shot.points().get(oneBasedIndex - 1);
    }

    private static void requireShot(ShotDefinition shot) {
        if (shot == null) throw new IllegalArgumentException("Shot is required");
    }
}
