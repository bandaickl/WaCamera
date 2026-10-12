package dev.wacamera.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** World-space wireframe for a camera marker; contains no rendering or Minecraft dependencies. */
public final class CameraMarkerGeometry {
    private static final double FRUSTUM_LENGTH = 2.0;
    private static final double ARROW_LENGTH = 3.0;

    private CameraMarkerGeometry() { }

    /** A line whose direction flag distinguishes the amber arrow from the cyan camera and frustum. */
    public record Segment(Vec3d from, Vec3d to, boolean direction) {
        public Segment {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /**
     * Returns immutable world-space lines anchored at the lens position.
     * The pose FOV is vertical, and aspectRatio is output width divided by output height.
     * Yaw zero faces +Z, yaw 90 faces -X, and positive pitch faces down.
     */
    public static List<Segment> segments(CameraPose pose, double aspectRatio) {
        Objects.requireNonNull(pose, "pose");
        if (!Double.isFinite(aspectRatio) || aspectRatio <= 0) {
            throw new IllegalArgumentException("Camera marker aspect ratio must be finite and positive");
        }
        double yaw = Math.toRadians(pose.yaw() % 360.0);
        double pitch = Math.toRadians(pose.pitch());
        double sinYaw = Math.sin(yaw), cosYaw = Math.cos(yaw);
        double sinPitch = Math.sin(pitch), cosPitch = Math.cos(pitch);
        // An analytic basis remains defined when looking straight up or down.
        Frame frame = new Frame(pose.position(),
                new Vec3d(-cosYaw, 0, -sinYaw),
                new Vec3d(-sinYaw * sinPitch, cosPitch, cosYaw * sinPitch),
                new Vec3d(-sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch));
        List<Segment> lines = new ArrayList<>(33);

        Vec3d[] back = frame.rectangle(0.25, 0.175, -0.55);
        Vec3d[] front = frame.rectangle(0.25, 0.175, -0.15);
        ring(lines, back);
        ring(lines, front);
        for (int i = 0; i < 4; i++) lines.add(new Segment(back[i], front[i], false));

        Vec3d[] lens = frame.rectangle(0.12, 0.12, -0.15);
        ring(lines, lens);
        for (Vec3d corner : lens) lines.add(new Segment(corner, pose.position(), false));

        double halfHeight = Math.tan(Math.toRadians(pose.fov()) / 2.0) * FRUSTUM_LENGTH;
        Vec3d[] frustum = frame.rectangle(halfHeight * aspectRatio, halfHeight, FRUSTUM_LENGTH);
        ring(lines, frustum);
        for (Vec3d corner : frustum) lines.add(new Segment(pose.position(), corner, false));

        Vec3d tip = frame.point(0, 0, ARROW_LENGTH);
        lines.add(new Segment(pose.position(), tip, true));
        lines.add(new Segment(tip, frame.point(-0.16, 0, 2.65), true));
        lines.add(new Segment(tip, frame.point(0.16, 0, 2.65), true));
        lines.add(new Segment(tip, frame.point(0, -0.16, 2.65), true));
        lines.add(new Segment(tip, frame.point(0, 0.16, 2.65), true));
        return List.copyOf(lines);
    }

    private static void ring(List<Segment> lines, Vec3d[] corners) {
        for (int i = 0; i < 4; i++) lines.add(new Segment(corners[i], corners[(i + 1) % 4], false));
    }

    private record Frame(Vec3d origin, Vec3d right, Vec3d up, Vec3d forward) {
        Vec3d point(double x, double y, double z) {
            return origin.add(right.multiply(x)).add(up.multiply(y)).add(forward.multiply(z));
        }

        Vec3d[] rectangle(double halfWidth, double halfHeight, double distance) {
            return new Vec3d[] {
                    point(-halfWidth, -halfHeight, distance), point(halfWidth, -halfHeight, distance),
                    point(halfWidth, halfHeight, distance), point(-halfWidth, halfHeight, distance)
            };
        }
    }
}
