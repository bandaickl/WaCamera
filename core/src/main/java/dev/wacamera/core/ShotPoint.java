package dev.wacamera.core;

public record ShotPoint(String id, double x, double y, double z, float yawDegrees, float pitchDegrees) {
    public ShotPoint {
        if (id == null || id.isBlank() || id.length() > 64) {
            throw new IllegalArgumentException("Point id must contain 1 to 64 characters");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yawDegrees) || !Float.isFinite(pitchDegrees)) {
            throw new IllegalArgumentException("Point coordinates and angles must be finite");
        }
        if (Math.abs(x) > 30_000_000 || Math.abs(y) > 30_000_000 || Math.abs(z) > 30_000_000) {
            throw new IllegalArgumentException("Point coordinates must be within 30,000,000 blocks");
        }
        if (pitchDegrees < -90 || pitchDegrees > 90) {
            throw new IllegalArgumentException("Pitch must be between -90 and 90 degrees");
        }
    }

    public Vec3d position() { return new Vec3d(x, y, z); }
}
