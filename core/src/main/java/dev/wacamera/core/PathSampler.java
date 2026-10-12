package dev.wacamera.core;

import java.util.List;

/**
 * Evaluates a shot on a real-time clock. Construct once, then sample on the render thread.
 * Look-at keeps the last valid yaw at a pole, and the last valid direction at coincidence.
 */
public final class PathSampler {
    private static final int SUBDIVISIONS = 256;
    private static final double EPSILON = 1e-9;
    private final ShotDefinition shot;
    private final List<ShotPoint> points;
    private final double[] distances;
    private final double[] parameters;
    private final double length;
    private float previousYaw;
    private float previousPitch;

    public PathSampler(ShotDefinition shot) {
        if (shot == null) throw new IllegalArgumentException("Shot is required");
        this.shot = shot;
        this.points = shot.points();
        this.previousYaw = wrapDegrees(points.get(0).yawDegrees());
        this.previousPitch = points.get(0).pitchDegrees();
        int subdivisions = isCurved() ? SUBDIVISIONS : 1;
        int count = (points.size() - 1) * subdivisions + 1;
        distances = new double[count];
        parameters = new double[count];
        Vec3d previous = points.get(0).position();
        double accumulated = 0;
        int index = 1;
        for (int segment = 0; segment < points.size() - 1; segment++) {
            for (int step = 1; step <= subdivisions; step++) {
                double parameter = segment + (double) step / subdivisions;
                Vec3d position = position(parameter);
                accumulated += position.distanceTo(previous);
                distances[index] = accumulated;
                parameters[index++] = parameter;
                previous = position;
            }
        }
        length = accumulated;
    }

    public CameraPose sample(double elapsedSeconds, Vec3d selfTarget) {
        if (!Double.isFinite(elapsedSeconds)) throw new IllegalArgumentException("Elapsed time must be finite");
        double fraction = Math.max(0, Math.min(1, elapsedSeconds / shot.durationSeconds()));
        double parameter = length <= EPSILON ? fraction * (points.size() - 1) : distanceParameter(fraction);
        Vec3d position = position(parameter);
        int index = Math.min(points.size() - 1, (int) Math.floor(parameter));
        ShotPoint start = points.get(index);
        ShotPoint end = points.get(Math.min(index + 1, points.size() - 1));
        double local = parameter - index;
        float startYaw = wrapDegrees(start.yawDegrees());
        float endYaw = wrapDegrees(end.yawDegrees());
        float yaw = wrapDegrees(startYaw + wrapDegrees(endYaw - (double) startYaw) * local);
        float pitch = (float) (start.pitchDegrees() + (end.pitchDegrees() - start.pitchDegrees()) * local);
        Vec3d target = switch (shot.lookMode()) {
            case "self" -> selfTarget;
            case "target" -> shot.target();
            default -> null;
        };
        if (target != null) {
            Vec3d direction = target.subtract(position);
            double horizontal = Math.hypot(direction.x(), direction.z());
            if (direction.length() <= EPSILON) {
                yaw = previousYaw;
                pitch = previousPitch;
            } else {
                yaw = horizontal <= EPSILON ? previousYaw
                        : wrapDegrees(Math.toDegrees(Math.atan2(-direction.x(), direction.z())));
                pitch = (float) -Math.toDegrees(Math.atan2(direction.y(), horizontal));
            }
        }
        previousYaw = yaw;
        previousPitch = pitch;
        return new CameraPose(position.x(), position.y(), position.z(), yaw, pitch, shot.fovDegrees());
    }

    public double length() { return length; }

    private boolean isCurved() { return "smooth".equals(shot.positionInterpolation()) && points.size() >= 3; }

    private double distanceParameter(double fraction) {
        if (fraction <= 0) return 0;
        if (fraction >= 1) return points.size() - 1;
        double desired = length * fraction;
        int low = 1;
        int high = distances.length - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (distances[middle] < desired) low = middle + 1;
            else high = middle;
        }
        double span = distances[low] - distances[low - 1];
        if (span <= EPSILON) return parameters[low];
        double local = (desired - distances[low - 1]) / span;
        return parameters[low - 1] + (parameters[low] - parameters[low - 1]) * local;
    }

    private Vec3d position(double parameter) {
        if (points.size() == 1 || parameter <= 0) return points.get(0).position();
        if (parameter >= points.size() - 1) return points.get(points.size() - 1).position();
        int segment = (int) Math.floor(parameter);
        double fraction = parameter - segment;
        Vec3d p1 = points.get(segment).position();
        Vec3d p2 = points.get(segment + 1).position();
        if (!isCurved() || p1.distanceTo(p2) <= EPSILON) return p1.lerp(p2, fraction);
        Vec3d p0 = p1.add(p1.subtract(p2));
        for (int before = segment - 1; before >= 0; before--) {
            Vec3d candidate = points.get(before).position();
            if (candidate.distanceTo(p1) > EPSILON) { p0 = candidate; break; }
        }
        Vec3d p3 = p2.add(p2.subtract(p1));
        for (int after = segment + 2; after < points.size(); after++) {
            Vec3d candidate = points.get(after).position();
            if (candidate.distanceTo(p2) > EPSILON) { p3 = candidate; break; }
        }
        // Centripetal Catmull-Rom: knot spacing is |Pi+1 - Pi|^0.5.
        double t0 = 0;
        double t1 = Math.sqrt(p0.distanceTo(p1));
        double t2 = t1 + Math.sqrt(p1.distanceTo(p2));
        double t3 = t2 + Math.sqrt(p2.distanceTo(p3));
        double t = t1 + (t2 - t1) * fraction;
        Vec3d a1 = blend(p0, p1, t0, t1, t);
        Vec3d a2 = blend(p1, p2, t1, t2, t);
        Vec3d a3 = blend(p2, p3, t2, t3, t);
        Vec3d b1 = blend(a1, a2, t0, t2, t);
        Vec3d b2 = blend(a2, a3, t1, t3, t);
        return blend(b1, b2, t1, t2, t);
    }

    private static Vec3d blend(Vec3d a, Vec3d b, double start, double end, double time) {
        return a.lerp(b, (time - start) / (end - start));
    }

    public static float wrapDegrees(double degrees) {
        double wrapped = degrees % 360;
        if (wrapped >= 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return (float) wrapped;
    }
}
