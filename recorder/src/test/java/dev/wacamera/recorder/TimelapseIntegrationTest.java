package dev.wacamera.recorder;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real video-only FFmpeg tests. Never opens an audio capture or playback device. */
class TimelapseIntegrationTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path temporary;

    private Path ffmpeg() {
        String configured = System.getenv("WACAMERA_TEST_FFMPEG");
        assumeTrue(configured != null && Files.isRegularFile(Path.of(configured)), "Set WACAMERA_TEST_FFMPEG for encoder integration");
        return Path.of(configured);
    }

    @Test void oneRealSecondAtTenTimesSpeedProducesThreeDifferentFramesAt30Fps() throws Exception {
        verifyOneSecond(30);
    }

    @Test void fractionalCaptureRate24FpsAtTenTimesSpeedPreservesFrameOrder() throws Exception {
        verifyOneSecond(24);
    }

    @Test void delayedReadbackKeepsTheCaptureTimestampAndDoesNotRepeatEarlierImages() throws Exception {
        verifyOneSecond(30, TimeUnit.MILLISECONDS.toNanos(150));
    }

    private void verifyOneSecond(int fps) throws Exception {
        verifyOneSecond(fps, 0);
    }

    private void verifyOneSecond(int fps, long readbackDelayNanos) throws Exception {
        Path binary = ffmpeg();
        RecordingOptions options = new RecordingOptions(binary, temporary, 64, 64, fps, 1,
                "libx264", null, true, 10); // Requested audio must not initialize Windows audio at >1x.
        try (FfmpegRecorder recorder = new FfmpegRecorder(options)) {
            recorder.start();
            assertEquals(0, recorder.elapsedSeconds());
            assertEquals(0, recorder.startNanos());
            long start = System.nanoTime();
            for (int frame = 0; frame < 3; frame++) {
                long at = start + Math.round(frame * 10_000_000_000.0 / fps);
                // The GPU image keeps its original capture timestamp even when a heavy camera
                // pass and asynchronous PBO mapping delay delivery beyond an output-frame period.
                waitUntil(at + (frame == 0 ? 0 : readbackDelayNanos));
                recorder.submitFrame(color(frame), at);
            }
            assertEquals(start, recorder.startNanos());
            assertEquals(fps / 10.0, recorder.captureFps(), 1e-12);
            waitUntil(start + 1_100_000_000L);
            Path video = recorder.finish().get(20, TimeUnit.SECONDS);
            assertEquals(3, recorder.framesWritten());
            assertEquals(0, recorder.repeatedFrames(), "An on-time sparse capture must not repeat a past slot");
            assertEquals(0, recorder.droppedFrames());
            assertEquals(1.0, recorder.elapsedSeconds(), 1e-9);
            assertNull(recorder.failure());
            assertFalse(Files.exists(recorder.recoveryDirectory().resolve("audio.wav")));
            assertFalse(Files.exists(recorder.recoveryDirectory().resolve("audio-clock.json")));
            assertVideo(binary, video, recorder.recoveryDirectory(), fps, 3);

            Path decoded = temporary.resolve("colors.rgb");
            ProcessTools.run(List.of(binary.toString(), "-v", "error", "-nostdin", "-i", video.toString(),
                    "-f", "rawvideo", "-pix_fmt", "rgb24", decoded.toString()), temporary.resolve("decode.log"),
                    temporary.resolve("decode-error.log"), Duration.ofSeconds(10));
            byte[] pixels = Files.readAllBytes(decoded);
            assertEquals(3 * 64 * 64 * 3, pixels.length);
            for (int frame = 0; frame < 3; frame++) {
                int pixel = frame * 64 * 64 * 3 + (32 * 64 + 32) * 3;
                for (int channel = 0; channel < 3; channel++) {
                    int value = Byte.toUnsignedInt(pixels[pixel + channel]);
                    assertTrue(channel == frame ? value > 200 : value < 40, "Unexpected color/order at frame " + frame);
                }
            }

            JsonObject metadata = json(recorder.recoveryDirectory().resolve("session.json"));
            assertEquals(10.0, metadata.get("playbackSpeed").getAsDouble());
            assertEquals(1.0, metadata.get("requestedDurationSeconds").getAsDouble());
            assertEquals(1.0, metadata.get("elapsedSeconds").getAsDouble());
            assertEquals(3.0 / fps, metadata.get("videoDurationSeconds").getAsDouble(), 1e-9);
            assertFalse(metadata.get("recordAudio").getAsBoolean());
        }
    }

    @Test void earlyFinishAndRecoveryPreserveShortenedTimelineAndRealProgress() throws Exception {
        Path binary = ffmpeg();
        try (FfmpegRecorder recorder = new FfmpegRecorder(new RecordingOptions(binary, temporary, 64, 64, 24,
                10, "libx264", null, false, 10))) {
            recorder.start();
            long start = System.nanoTime();
            recorder.submitFrame(color(0), start);
            waitUntil(start + 700_000_000L);
            var finishing = recorder.finish();
            double stopped = recorder.elapsedSeconds();
            assertTrue(stopped >= 0.7 && stopped < 2, "Stopped progress must remain real elapsed time");
            Path video = finishing.get(20, TimeUnit.SECONDS);
            assertEquals(stopped, recorder.elapsedSeconds());
            long expectedFrames = (long) Math.ceil(stopped * 24 / 10);
            assertEquals(expectedFrames, recorder.framesWritten());
            assertVideo(binary, video, recorder.recoveryDirectory(), 24, expectedFrames);

            // Simulate a retained interrupted fragment with stale metadata. Real encoded packets,
            // not requested real duration or a second speed conversion, determine recovery length.
            Path session = recorder.recoveryDirectory();
            Files.copy(video, session.resolve("video.fragmented.mp4"), StandardCopyOption.REPLACE_EXISTING);
            JsonObject metadata = json(session.resolve("session.json"));
            metadata.addProperty("status", "recording");
            metadata.addProperty("framesWritten", 0);
            metadata.addProperty("recordAudio", true); // Must stay silent even with inconsistent stored preference.
            Files.writeString(session.resolve("session.json"), new Gson().toJson(metadata));
            // Completion publication can precede the worker's final lock release by a few instructions.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (SessionLock.isActive(session) && System.nanoTime() < deadline) Thread.sleep(5);
            assertFalse(RecordingRecovery.listRecoverySessions(temporary).get(0).audioRequested());
            Path recovered = RecordingRecovery.recoverFromSession(session, binary).get(20, TimeUnit.SECONDS);
            assertVideo(binary, recovered, temporary.resolve("recovered-probe"), 24, expectedFrames);
            assertTrue(Files.isRegularFile(session.resolve("video.fragmented.mp4")));
            JsonObject restored = json(session.resolve("session.json"));
            assertEquals(10.0, restored.get("playbackSpeed").getAsDouble());
            assertEquals(expectedFrames / 24.0, restored.get("recoveredVideoDurationSeconds").getAsDouble(), 1e-9);
            assertFalse(restored.get("recoveredAudio").getAsBoolean());
        }
    }

    @Test void aFiveSecondCaptureIntervalDoesNotTriggerTheTwoSecondLagWatchdog() throws Exception {
        Path binary = ffmpeg();
        try (FfmpegRecorder recorder = new FfmpegRecorder(new RecordingOptions(binary, temporary, 64, 64, 24,
                2.3, "libx264", null, true, 120))) {
            recorder.start();
            long start = System.nanoTime();
            recorder.submitFrame(color(2), start);
            waitUntil(start + 2_400_000_000L);
            Path video = recorder.finish().get(20, TimeUnit.SECONDS);
            assertNull(recorder.failure());
            assertEquals(1, recorder.framesWritten());
            assertEquals(0, recorder.repeatedFrames());
            assertEquals(2.3, recorder.elapsedSeconds(), 1e-9);
            assertVideo(binary, video, recorder.recoveryDirectory(), 24, 1);
        }
    }

    private static void assertVideo(Path binary, Path video, Path reports, int fps, long frames) throws Exception {
        Files.createDirectories(reports);
        Path probe = reports.resolve("timelapse-probe.json");
        ProcessTools.run(List.of(ExecutableResolver.resolveFfprobe(binary).toString(), "-v", "error", "-show_entries",
                "stream=codec_type,codec_name,nb_frames,avg_frame_rate,duration", "-of", "json", video.toString()),
                probe, reports.resolve("timelapse-probe-error.log"), Duration.ofSeconds(10));
        var streams = json(probe).getAsJsonArray("streams");
        assertEquals(1, streams.size(), "A timelapse MP4 must contain video only");
        JsonObject stream = streams.get(0).getAsJsonObject();
        assertEquals("video", stream.get("codec_type").getAsString());
        assertEquals("h264", stream.get("codec_name").getAsString());
        assertEquals(frames, stream.get("nb_frames").getAsLong());
        assertEquals(fps + "/1", stream.get("avg_frame_rate").getAsString());
        assertEquals(frames / (double) fps, stream.get("duration").getAsDouble(), 1e-5);
    }

    private static JsonObject json(Path path) throws Exception {
        return new Gson().fromJson(Files.readString(path), JsonObject.class);
    }

    private static ByteBuffer color(int channel) {
        ByteBuffer rgba = ByteBuffer.allocate(64 * 64 * 4);
        for (int i = 0; i < 64 * 64; i++) {
            for (int c = 0; c < 3; c++) rgba.put((byte) (c == channel ? 255 : 0));
            rgba.put((byte) 255);
        }
        return rgba.flip();
    }

    private static void waitUntil(long at) throws InterruptedException {
        for (long remaining; (remaining = at - System.nanoTime()) > 0;) TimeUnit.NANOSECONDS.sleep(remaining);
    }
}
