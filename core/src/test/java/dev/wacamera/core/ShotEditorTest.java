package dev.wacamera.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ShotEditorTest {
    private static ShotDefinition shot(List<ShotPoint> points) {
        return new ShotDefinition(2, "Camera route", "minecraft:the_nether", 41.5,
                7, "smooth", "constant", "target", 83, points, new Vec3d(20, 80, -10), 3.25);
    }

    private static ShotDefinition withPoints(ShotDefinition source, List<ShotPoint> points) {
        return new ShotDefinition(source.schemaVersion(), source.name(), source.dimension(), source.durationSeconds(),
                source.countdownSeconds(), source.positionInterpolation(), source.speedMode(), source.lookMode(),
                source.fovDegrees(), points, source.target(), source.playbackSpeed());
    }

    private static ShotPoint point(String id) { return new ShotPoint(id, 1, 64, 3, 10, 20); }

    @Test void replacingKeepsPointIdentityAndEveryShotSettingWithoutMutatingOriginal() {
        ShotPoint first = point("first"), second = point("second"), third = point("third");
        ShotDefinition original = shot(List.of(first, second, third));

        ShotDefinition edited = ShotEditor.setPoint(original, 2, -18.25, 90.75, 33, 215, -34);

        assertEquals(withPoints(original, List.of(first,
                new ShotPoint("second", -18.25, 90.75, 33, -145, -34), third)), edited);
        assertEquals(List.of(first, second, third), original.points());
        assertThrows(UnsupportedOperationException.class, () -> edited.points().clear());
    }

    @Test void appendingAndNumberedNextPointKeepExistingPointsAndGenerateDistinctIds() {
        ShotDefinition original = shot(List.of(point("original")));
        ShotDefinition appended = ShotEditor.appendPoint(original, 10, 65, 15, 0, 0);
        ShotDefinition next = ShotEditor.setPoint(appended, 3, 20, 70, 25, 360, 5);

        assertEquals(1, original.points().size());
        assertEquals(2, appended.points().size());
        assertEquals(3, next.points().size());
        assertEquals(appended.points(), next.points().subList(0, 2));
        assertEquals(3, next.points().stream().map(ShotPoint::id).distinct().count());
        assertEquals(new Vec3d(20, 70, 25), next.points().get(2).position());
        assertEquals(0, next.points().get(2).yawDegrees());
        assertEquals(withPoints(original, next.points()), next);
    }

    @Test void sixtyFourthPointIsAllowedAndCanBeReplacedButSixtyFifthIsRejected() {
        List<ShotPoint> points = IntStream.rangeClosed(1, 63).mapToObj(i -> point("p" + i)).toList();
        ShotDefinition full = ShotEditor.appendPoint(shot(points), 64, 64, 64, 0, 0);
        String lastId = full.points().get(63).id();

        ShotDefinition replaced = ShotEditor.setPoint(full, 64, -64, 64, -64, 180, 90);

        assertEquals(64, replaced.points().size());
        assertEquals(lastId, replaced.points().get(63).id());
        assertEquals(full.points().subList(0, 63), replaced.points().subList(0, 63));
        assertThrows(IllegalArgumentException.class, () -> ShotEditor.appendPoint(full, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ShotEditor.setPoint(full, 65, 0, 0, 0, 0, 0));
    }

    @Test void pointNumbersRejectGapsZeroNegativesAndOverflow() {
        ShotDefinition original = shot(List.of(point("a"), point("b")));
        for (int index : new int[]{Integer.MIN_VALUE, -1, 0, 4, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> ShotEditor.setPoint(original, index, 0, 0, 0, 0, 0));
        }
        assertSame(original.points().get(0), ShotEditor.pointAt(original, 1));
        assertSame(original.points().get(1), ShotEditor.pointAt(original, 2));
        for (int index : new int[]{-1, 0, 3, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> ShotEditor.pointAt(original, index));
        }
        assertEquals(2, original.points().size());
    }

    @Test void finiteYawWrapsAndPitchLimitsAreStrictBeforeFloatConversion() {
        ShotDefinition original = shot(List.of(point("a")));
        double[][] cases = {{180, -180}, {-540, -180}, {725, 5}, {-725, -5}, {360, 0},
                {Math.nextDown(180d), -180}};
        for (double[] values : cases) {
            assertEquals(values[1], ShotEditor.setPoint(original, 1, 0, 0, 0, values[0], 0)
                    .points().get(0).yawDegrees());
        }
        for (double pitch : new double[]{-90, 90}) {
            assertEquals(pitch, ShotEditor.setPoint(original, 1, 0, 0, 0, 0, pitch).points().get(0).pitchDegrees());
        }
        for (double pitch : new double[]{Math.nextDown(-90d), Math.nextUp(90d), -100, 100}) {
            assertThrows(IllegalArgumentException.class, () -> ShotEditor.setPoint(original, 1, 0, 0, 0, 0, pitch));
        }
        float wrapped = ShotEditor.setPoint(original, 1, 0, 0, 0, Double.MAX_VALUE, 0).points().get(0).yawDegrees();
        assertTrue(Float.isFinite(wrapped) && wrapped >= -180 && wrapped < 180);
    }

    @Test void allCoordinatesAndAnglesRejectNanAndInfinityWithoutChangingTheShot() {
        ShotDefinition original = shot(List.of(point("a")));
        for (int index = 0; index < 5; index++) {
            for (double invalid : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
                double[] input = {0, 64, 0, 0, 0};
                input[index] = invalid;
                assertThrows(IllegalArgumentException.class, () -> ShotEditor.setPoint(original, 1,
                        input[0], input[1], input[2], input[3], input[4]));
            }
        }
        assertEquals(List.of(point("a")), original.points());
    }

    @Test void coordinateBoundaryIsAcceptedAndEachAxisOutsideItIsRejected() {
        ShotDefinition original = shot(List.of(point("a")));
        assertEquals(new Vec3d(-30_000_000, 30_000_000, -30_000_000), ShotEditor.setPoint(original,
                1, -30_000_000, 30_000_000, -30_000_000, 0, 0).points().get(0).position());
        for (int axis = 0; axis < 3; axis++) {
            for (double invalid : new double[]{Math.nextUp(30_000_000d), Math.nextDown(-30_000_000d)}) {
                double[] position = {0, 0, 0};
                position[axis] = invalid;
                assertThrows(IllegalArgumentException.class, () -> ShotEditor.appendPoint(original,
                        position[0], position[1], position[2], 0, 0));
            }
        }
    }
}
