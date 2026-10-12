package dev.wacamera.recorder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CfrTimelineTest {
    @Test void tenSecondsAlwaysContainsThreeHundredFrames() {
        CfrTimeline timeline = new CfrTimeline(30);
        assertEquals(300, timeline.framesForDuration(10_000_000_000L));
        assertEquals(0, timeline.framesForDuration(0));
        assertEquals(1, timeline.framesForDuration(1));
        assertEquals(301, timeline.framesForDuration(10_000_000_001L));
    }

    @Test void aRenderStallDoesNotCompressTheTimeline() {
        CfrTimeline timeline = new CfrTimeline(30);
        assertEquals(0, timeline.nearestSlot(0));
        assertEquals(1, timeline.nearestSlot(33_333_333));
        assertEquals(30, timeline.nearestSlot(1_000_000_000));
        assertEquals(9, timeline.readyFrames(350_000_000, 66_666_666));
        assertEquals(0, timeline.readyFrames(20_000_000, 66_666_666));
    }

    @Test void audioClockMappingDoesNotAssumeSharedOrigins() {
        AudioCapture.Metadata metadata = new AudioCapture.Metadata(48_000, 2, 480_480,
                9_000_000_000L, 19_000_000_000L, 100_000_000_000L, 1000);
        assertEquals(1.001, metadata.tempoRatio(), 1e-12);
        assertEquals(-0.25, metadata.offsetSeconds(109_250_000_000L), 1e-12);
    }

    @Test void invalidOptionsFailBeforeAllocatingFrames() {
        assertThrows(IllegalArgumentException.class, () -> new RecordingOptions(null, java.nio.file.Path.of("."),
                1919, 1080, 30, 10, "libx264", null, false));
        assertThrows(IllegalArgumentException.class, () -> new RecordingOptions(null, java.nio.file.Path.of("."),
                1280, 720, 30, Double.NaN, "libx264", null, false));
        assertThrows(IllegalArgumentException.class, () -> new RecordingOptions(null, java.nio.file.Path.of("."),
                1280, 720, 30, 10, "libx264", null, true));
    }

    @Test void timelapseMapsRealTimeToOutputSlotsWithoutRoundingCaptureFps() {
        CfrTimeline timeline = new CfrTimeline(24, 10);
        assertEquals(0, timeline.nearestSlot(0));
        assertEquals(1, timeline.nearestSlot(416_666_667));
        assertEquals(2, timeline.nearestSlot(833_333_333));
        assertEquals(24, timeline.nearestSlot(10_000_000_000L));
        assertEquals(3, timeline.framesForDuration(1_000_000_000L));
        assertEquals(24, timeline.framesForDuration(10_000_000_000L));
        assertEquals(25, timeline.framesForDuration(10_000_000_001L));
        assertEquals(2, timeline.readyFrames(500_000_000, 50_000_000));
        assertEquals(0, timeline.readyFrames(40_000_000, 50_000_000));
        assertEquals(1.0, timeline.secondsForFrames(24));
        assertEquals(10.0, timeline.captureSecondsForFrames(24));
        assertEquals(5.0, new CfrTimeline(24, 120).captureSecondsForFrames(1));
    }

    @Test void fractionalSpeedAndSubFrameDurationKeepOneOutputFrame() {
        RecordingOptions options = new RecordingOptions(null, java.nio.file.Path.of("."), 64, 64, 24,
                1, "libx264", null, false, 2.5);
        assertEquals(10, options.maximumFrames());
        assertEquals(9.6, options.captureFps(), 1e-12);
        assertEquals(1, new RecordingOptions(null, java.nio.file.Path.of("."), 64, 64, 24,
                0.01, "libx264", null, false, 120).maximumFrames());
        assertEquals(10, new CfrTimeline(24, 2.5).framesForDuration(1_000_000_000));
    }

    @Test void timelapseDisablesAudioBeforeCheckingTheHelperAndOldConstructorKeepsAudio() {
        RecordingOptions fast = new RecordingOptions(null, java.nio.file.Path.of("."), 64, 64, 30,
                1, "libx264", null, true, 10);
        assertFalse(fast.recordAudio());
        assertEquals(3, fast.maximumFrames());
        RecordingOptions old = new RecordingOptions(null, java.nio.file.Path.of("."), 64, 64, 30,
                1, "libx264", java.nio.file.Path.of("audio-helper"), true);
        assertEquals(1.0, old.playbackSpeed());
        assertTrue(old.recordAudio());
        assertEquals(30, old.maximumFrames());
    }

    @Test void invalidSpeedsFailBeforeStartingProcesses() {
        for (double speed : new double[]{0, 0.99, 120.01, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new RecordingOptions(null, java.nio.file.Path.of("."),
                    64, 64, 24, 1, "libx264", null, true, speed));
            assertThrows(IllegalArgumentException.class, () -> new CfrTimeline(24, speed));
        }
    }
}
