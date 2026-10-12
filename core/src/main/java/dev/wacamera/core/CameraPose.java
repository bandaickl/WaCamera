package dev.wacamera.core;

/** Yaw/pitch use Minecraft degrees; the position is the camera lens, not feet. */
public record CameraPose(double x, double y, double z, float yaw, float pitch, float fov) {
    public CameraPose {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(fov)) {
            throw new IllegalArgumentException("Camera pose must be finite");
        }
        if (pitch < -90 || pitch > 90 || fov <= 0 || fov >= 180) {
            throw new IllegalArgumentException("Invalid camera pitch or field of view");
        }
    }

    public Vec3d position() { return new Vec3d(x, y, z); }
}
