package dev.wacamera.recorder;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in real-time soak. No audio playback is created under any configuration. */
class LongRecordingIntegrationTest {
    @Test void silent720p30SoakKeepsFramesAndBoundedMemory() throws Exception {
        String secondsValue = System.getenv("WACAMERA_LONG_TEST_SECONDS");
        assumeTrue(secondsValue != null && !secondsValue.isBlank(), "Set WACAMERA_LONG_TEST_SECONDS=600 to enable the silent soak");
        double seconds = Double.parseDouble(secondsValue);
        assertTrue(seconds >= 1 && seconds <= 3600);
        String configured = System.getenv("WACAMERA_TEST_FFMPEG");
        assumeTrue(configured != null && Files.isRegularFile(Path.of(configured)));
        String helper = System.getenv("WACAMERA_TEST_AUDIO_HELPER");
        boolean captureSilentAudio = helper != null && Files.isRegularFile(Path.of(helper));
        Path output = Path.of("build", "long-recording-test").toAbsolutePath();
        Files.createDirectories(output);
        FfmpegRecorder recorder = new FfmpegRecorder(new RecordingOptions(Path.of(configured), output, 1280, 720, 30,
                seconds, "libx264", captureSilentAudio ? Path.of(helper) : null, captureSilentAudio));
        ByteBuffer pixels = ByteBuffer.allocate(1280 * 720 * 4);
        JsonArray memory = new JsonArray();
        long baseline = 0, peak = 0;
        long submitted = 0;
        long started = 0;
        Path video;
        try {
            recorder.start();
            started = System.nanoTime();
            long nextMemorySample = 0;
            long frameLimit = (long) Math.ceil(seconds * 30);
            for (long slot = 0; slot < frameLimit && recorder.isRecording(); slot++) {
                long target = started + Math.round(slot * (1_000_000_000.0 / 30));
                while (System.nanoTime() < target) LockSupport.parkNanos(Math.min(target - System.nanoTime(), 1_000_000));
                Arrays.fill(pixels.array(), (byte) (16 + slot % 220));
                recorder.submitFrame(pixels, System.nanoTime());
                submitted++;
                long elapsed = System.nanoTime() - started;
                if (elapsed >= nextMemorySample) {
                    long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                    if (baseline == 0) baseline = used;
                    peak = Math.max(peak, used);
                    JsonObject sample = new JsonObject();
                    sample.addProperty("elapsedSeconds", elapsed / 1_000_000_000.0);
                    sample.addProperty("heapUsedBytes", used);
                    sample.addProperty("framesWritten", recorder.framesWritten());
                    memory.add(sample);
                    nextMemorySample += TimeUnit.SECONDS.toNanos(1);
                }
            }
            long untilEnd = (long) (seconds * 1_000_000_000.0) - (System.nanoTime() - started);
            if (untilEnd > 0) TimeUnit.NANOSECONDS.sleep(untilEnd + 100_000_000L);
            video = recorder.finish().get(120, TimeUnit.SECONDS);
        } finally {
            recorder.close();
            JsonObject report = new JsonObject();
            report.addProperty("requestedSeconds", seconds);
            report.addProperty("audioPlayback", false);
            report.addProperty("processScopedSilentAudio", captureSilentAudio);
            report.addProperty("submittedFrames", submitted);
            report.addProperty("framesWritten", recorder.framesWritten());
            report.addProperty("repeatedFrames", recorder.repeatedFrames());
            report.addProperty("droppedFrames", recorder.droppedFrames());
            report.addProperty("heapBaselineBytes", baseline);
            report.addProperty("heapPeakBytes", peak);
            report.addProperty("recoveryDirectory", String.valueOf(recorder.recoveryDirectory()));
            report.addProperty("failure", recorder.failure());
            report.add("memorySamples", memory);
            Files.writeString(output.resolve("latest-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
        assertEquals((long) Math.ceil(seconds * 30), recorder.framesWritten());
        assertTrue(Files.isRegularFile(video));
        assertNull(recorder.failure());
        assertTrue(peak - baseline < 128L * 1024 * 1024, "Heap grew more than the recording buffer budget");
        if (captureSilentAudio) assertTrue(Files.readString(recorder.recoveryDirectory().resolve("ffprobe.json")).contains("aac"));
    }
}
