package dev.wacamera.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathSamplerTest {
    private static ShotPoint point(String id, double x, double y, double z, float yaw) {
        return new ShotPoint(id, x, y, z, yaw, 0);
    }

    private static ShotDefinition shot(String interpolation, String look, List<ShotPoint> points, Vec3d target) {
        return new ShotDefinition(1, "test", "minecraft:overworld", 10, 3,
                interpolation, "constant", look, 70, points, target);
    }

    @Test void endpointsAndOutOfRangeTimesAreClamped() {
        PathSampler sampler = new PathSampler(shot("smooth", "registered", List.of(
                point("a", 5, 7, -9, 10), point("b", 30, 18, 9, 60), point("c", 40, 4, 60, 80)), null));
        assertEquals(new Vec3d(5, 7, -9), sampler.sample(-1, null).position());
        assertEquals(new Vec3d(40, 4, 60), sampler.sample(11, null).position());
        assertEquals(80, sampler.sample(10, null).yaw(), 1e-5);
        assertThrows(IllegalArgumentException.class, () -> sampler.sample(Double.NaN, null));
    }

    @Test void unequalStraightSegmentsHaveConstantSpeed() {
        PathSampler sampler = new PathSampler(shot("linear", "registered", List.of(
                point("a", 0, 0, 0, 0), point("b", 1, 0, 0, 10), point("c", 10, 0, 0, 100)), null));
        for (int second = 0; second <= 10; second++) {
            assertEquals(second, sampler.sample(second, null).x(), 1e-8);
            assertEquals(second * 10, sampler.sample(second, null).yaw(), 1e-5);
        }
    }

    @Test void curvedPathUsesDistanceRatherThanPointIndexForSpeed() {
        PathSampler sampler = new PathSampler(shot("smooth", "registered", List.of(
                point("a", 0, 0, 0, 0), point("b", 2, 3, 0, 10),
                point("c", 30, 8, 12, 20), point("d", 70, 8, 9, 30)), null));
        Vec3d previous = sampler.sample(0, null).position();
        double expectedStep = sampler.length() / 1000;
        for (int step = 1; step <= 1000; step++) {
            Vec3d current = sampler.sample(step / 100.0, null).position();
            assertEquals(expectedStep, current.distanceTo(previous), expectedStep * 0.03);
            previous = current;
        }
    }

    @Test void twoPointSmoothPathIsStraight() {
        PathSampler sampler = new PathSampler(shot("smooth", "registered", List.of(
                point("a", 0, 0, 0, 0), point("b", 10, 20, 30, 0)), null));
        assertEquals(new Vec3d(5, 10, 15), sampler.sample(5, null).position());
    }

    @Test void duplicatePositionsDoNotCauseInvalidCurveArithmetic() {
        PathSampler sampler = new PathSampler(shot("smooth", "registered", List.of(
                point("a", 0, 0, 0, 0), point("b", 0, 0, 0, 15),
                point("c", 10, 0, 5, 30), point("d", 10, 0, 5, 45),
                point("e", 30, 10, 10, 60)), null));
        for (int frame = 0; frame <= 300; frame++) {
            CameraPose pose = sampler.sample(frame / 30.0, null);
            assertTrue(Double.isFinite(pose.x()) && Double.isFinite(pose.y()) && Double.isFinite(pose.z()));
        }
        assertEquals(new Vec3d(30, 10, 10), sampler.sample(10, null).position());
    }

    @Test void stationaryShotsRetainRotationsAndUseShortestYawWrap() {
        PathSampler sampler = new PathSampler(shot("smooth", "registered", List.of(
                point("a", 1, 2, 3, 179), point("b", 1, 2, 3, -179)), null));
        CameraPose midway = sampler.sample(5, null);
        assertEquals(new Vec3d(1, 2, 3), midway.position());
        assertEquals(180, Math.abs(midway.yaw()), 1e-5);
        assertEquals(-179, sampler.sample(10, null).yaw(), 1e-5);
    }

    @Test void lookAtUsesMinecraftAxesAndKeepsDirectionAtCoincidence() {
        PathSampler sampler = new PathSampler(shot("linear", "self", List.of(point("a", 0, 0, 0, 12)), null));
        assertEquals(-90, sampler.sample(0, new Vec3d(10, 0, 0)).yaw(), 1e-5);
        CameraPose pole = sampler.sample(1, new Vec3d(0, 10, 0));
        assertEquals(-90, pole.yaw(), 1e-5);
        assertEquals(-90, pole.pitch(), 1e-5);
        CameraPose coincidence = sampler.sample(2, new Vec3d(0, 0, 0));
        assertEquals(pole.yaw(), coincidence.yaw());
        assertEquals(pole.pitch(), coincidence.pitch());
    }

    @Test void specifiedTargetAndSinglePointWork() {
        PathSampler sampler = new PathSampler(shot("linear", "target", List.of(point("a", 1, 2, 3, 25)), new Vec3d(1, 2, 10)));
        assertEquals(new Vec3d(1, 2, 3), sampler.sample(5, null).position());
        assertEquals(0, sampler.sample(5, null).yaw(), 1e-5);
        assertEquals(0, sampler.length());
    }
}
