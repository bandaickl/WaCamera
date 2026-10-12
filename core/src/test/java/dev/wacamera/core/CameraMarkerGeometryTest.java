package dev.wacamera.core;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CameraMarkerGeometryTest {
    private static final double EPSILON = 1e-9;

    @Test void directionArrowUsesMinecraftCardinalDirections() {
        float[] yaw = {0, 90, 180, -90, 450};
        Vec3d[] tips = {new Vec3d(0, 0, 3), new Vec3d(-3, 0, 0), new Vec3d(0, 0, -3),
                new Vec3d(3, 0, 0), new Vec3d(-3, 0, 0)};
        for (int i = 0; i < yaw.length; i++) {
            var shaft = arrowShaft(geometry(yaw[i], 0));
            assertVector(new Vec3d(0, 0, 0), shaft.from());
            assertVector(tips[i], shaft.to());
        }
    }

    @Test void bodyLensAndFrustumRotateTogetherAndRemainDefinedAtPitchPoles() {
        List<CameraMarkerGeometry.Segment> original = geometry(0, 0);
        assertTransformed(original, geometry(90, 0), p -> new Vec3d(-p.z(), p.y(), p.x()));
        assertTransformed(original, geometry(0, 90), p -> new Vec3d(p.x(), -p.z(), p.y()));
        assertTransformed(original, geometry(0, -90), p -> new Vec3d(p.x(), p.z(), -p.y()));
        for (float pitch : new float[] {-90, 90}) {
            List<CameraMarkerGeometry.Segment> tilted = geometry(37, pitch);
            assertVector(new Vec3d(0, pitch > 0 ? -3 : 3, 0), arrowShaft(tilted).to());
            for (int i = 0; i < tilted.size(); i++) {
                var line = tilted.get(i);
                assertTrue(Double.isFinite(line.from().length()) && Double.isFinite(line.to().length()));
                assertEquals(original.get(i).from().distanceTo(original.get(i).to()),
                        line.from().distanceTo(line.to()), EPSILON);
            }
        }
    }

    @Test void frustumUsesVerticalFovAndOutputAspectRatio() {
        for (double aspect : new double[] {16.0 / 9.0, 1, 9.0 / 16.0}) {
            List<Vec3d> corners = CameraMarkerGeometry.segments(new CameraPose(0, 0, 0, 0, 0, 90), aspect)
                    .stream().filter(segment -> !segment.direction())
                    .flatMap(segment -> Stream.of(segment.from(), segment.to()))
                    .filter(point -> Math.abs(point.z() - 2) < EPSILON).distinct().toList();
            assertEquals(4, corners.size());
            for (Vec3d corner : corners) {
                assertEquals(2, Math.abs(corner.y()), EPSILON);
                assertEquals(2 * aspect, Math.abs(corner.x()), EPSILON);
            }
        }
    }

    @Test void movingCameraPreservesItsGeometryAndDirectionClassification() {
        CameraPose pose = new CameraPose(0, 0, 0, 127, -32, 70);
        Vec3d translation = new Vec3d(-135.25, 82.75, 2050.125);
        List<CameraMarkerGeometry.Segment> original = CameraMarkerGeometry.segments(pose, 1.6);
        List<CameraMarkerGeometry.Segment> moved = CameraMarkerGeometry.segments(new CameraPose(
                translation.x(), translation.y(), translation.z(), pose.yaw(), pose.pitch(), pose.fov()), 1.6);
        assertTransformed(original, moved, point -> point.add(translation));
        assertThrows(UnsupportedOperationException.class, moved::clear);
    }

    @Test void invalidAspectRatiosAreRejected() {
        CameraPose pose = new CameraPose(0, 0, 0, 0, 0, 70);
        for (double aspect : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> CameraMarkerGeometry.segments(pose, aspect));
        }
    }

    private static List<CameraMarkerGeometry.Segment> geometry(float yaw, float pitch) {
        return CameraMarkerGeometry.segments(new CameraPose(0, 0, 0, yaw, pitch, 70), 16.0 / 9.0);
    }

    private static CameraMarkerGeometry.Segment arrowShaft(List<CameraMarkerGeometry.Segment> lines) {
        return lines.stream().filter(CameraMarkerGeometry.Segment::direction)
                .max(Comparator.comparingDouble(line -> line.from().distanceTo(line.to()))).orElseThrow();
    }

    private static void assertTransformed(List<CameraMarkerGeometry.Segment> original,
                                          List<CameraMarkerGeometry.Segment> transformed,
                                          UnaryOperator<Vec3d> operation) {
        assertEquals(original.size(), transformed.size());
        for (int i = 0; i < original.size(); i++) {
            assertVector(operation.apply(original.get(i).from()), transformed.get(i).from());
            assertVector(operation.apply(original.get(i).to()), transformed.get(i).to());
            assertEquals(original.get(i).direction(), transformed.get(i).direction());
        }
    }

    private static void assertVector(Vec3d expected, Vec3d actual) {
        assertEquals(expected.x(), actual.x(), EPSILON);
        assertEquals(expected.y(), actual.y(), EPSILON);
        assertEquals(expected.z(), actual.z(), EPSILON);
    }
}
