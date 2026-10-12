package dev.wacamera.core;

/** An immutable world-space vector independent of Minecraft classes. */
public record Vec3d(double x, double y, double z) {
    public Vec3d {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Vector coordinates must be finite");
        }
    }

    public Vec3d add(Vec3d other) { return new Vec3d(x + other.x, y + other.y, z + other.z); }
    public Vec3d subtract(Vec3d other) { return new Vec3d(x - other.x, y - other.y, z - other.z); }
    public Vec3d multiply(double factor) { return new Vec3d(x * factor, y * factor, z * factor); }
    public double length() { return Math.hypot(Math.hypot(x, y), z); }
    public double distanceTo(Vec3d other) { return subtract(other).length(); }
    public Vec3d lerp(Vec3d other, double fraction) {
        return new Vec3d(x + (other.x - x) * fraction, y + (other.y - y) * fraction,
                z + (other.z - z) * fraction);
    }
}
