package dev.wacamera.recorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.CleanupMode;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs real FFmpeg when WACAMERA_TEST_FFMPEG names a local executable. */
class FfmpegRecorderIntegrationTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path temporary;

    private Path ffmpeg() {
        String configured = System.getenv("WACAMERA_TEST_FFMPEG");
        assumeTrue(configured != null && Files.isRegularFile(Path.of(configured)), "Set WACAMERA_TEST_FFMPEG for real encoder integration tests");
        return Path.of(configured);
    }

    @Test void oneCapturedFrameProducesAFullSecondAndOwnsItsPixels() throws Exception {
        Path binary = ffmpeg();
        Path output = temporary.resolve("日本語の 保存先");
        FfmpegRecorder recorder = new FfmpegRecorder(new RecordingOptions(binary, output, 64, 64, 30, 1,
                "libx264", null, false));
        recorder.start();
        assertEquals(0, recorder.startNanos());
        assertEquals(0, recorder.elapsedSeconds());
        ByteBuffer pixels = redTopBlueBottom();
        long start = System.nanoTime();
        recorder.submitFrame(pixels, start);
        assertEquals(start, recorder.startNanos());
        assertEquals(0, pixels.position());
        // Renderer buffers are immediately reusable; later mutations must not affect the encoded image.
        while (pixels.hasRemaining()) pixels.put((byte) 0);
        pixels.rewind();
        Thread.sleep(1_150);
        Path video = recorder.finish().get(20, TimeUnit.SECONDS);
        assertSame(recorder.finish(), recorder.finish());
        assertTrue(Files.size(video) > 100);
        assertEquals(30, recorder.framesWritten());
        assertEquals(29, recorder.repeatedFrames());
        assertNull(recorder.failure());
        assertFalse(recorder.isRecording());
        assertFalse(Files.exists(recorder.recoveryDirectory().resolve("video.fragmented.mp4")));
        Path decoded = temporary.resolve("first-frame.rgb");
        ProcessTools.run(List.of(binary.toString(), "-v", "error", "-nostdin", "-i", video.toString(), "-frames:v", "1",
                "-f", "rawvideo", "-pix_fmt", "rgb24", decoded.toString()), temporary.resolve("decode.log"),
                temporary.resolve("decode-error.log"), Duration.ofSeconds(10));
        byte[] rgb = Files.readAllBytes(decoded);
        assertEquals(64 * 64 * 3, rgb.length);
        int top = (8 * 64 + 8) * 3;
        int bottom = (56 * 64 + 8) * 3;
        assertTrue(Byte.toUnsignedInt(rgb[top]) > 200, "Top must remain red; first frame must not be black or flipped");
        assertTrue(Byte.toUnsignedInt(rgb[bottom + 2]) > 200, "Bottom must remain blue");
    }

    @Test void stoppingWithoutAFrameNeverReportsAnEmptyVideoAsSuccess() throws Exception {
        FfmpegRecorder recorder = new FfmpegRecorder(new RecordingOptions(ffmpeg(), temporary, 64, 64, 30, 10,
                "libx264", null, false));
        recorder.start();
        assertThrows(java.util.concurrent.ExecutionException.class, () -> recorder.finish().get(20, TimeUnit.SECONDS));
        assertNotNull(recorder.failure());
        assertTrue(Files.exists(recorder.recoveryDirectory().resolve("session.json")));
    }

    @Test void unavailableRequestedEncoderFallsBackBeforeRecording() throws Exception {
        FfmpegRecorder recorder = new FfmpegRecorder(new RecordingOptions(ffmpeg(), temporary, 64, 64, 30, 0.2,
                "not_an_encoder", null, false));
        recorder.start();
        recorder.submitFrame(redTopBlueBottom(), System.nanoTime());
        Thread.sleep(300);
        assertTrue(Files.isRegularFile(recorder.finish().get(20, TimeUnit.SECONDS)));
        assertEquals(6, recorder.framesWritten());
        assertNull(recorder.failure());
    }

    @Test void windowsProcessCaptureRecordsASilentProcessWithoutPlayingSound() throws Exception {
        Path binary = ffmpeg();
        assumeTrue("true".equalsIgnoreCase(System.getenv("WACAMERA_TEST_WINDOWS_AUDIO")),
                "Set WACAMERA_TEST_WINDOWS_AUDIO=true to explicitly enable Windows process audio capture");
        FfmpegRecorder recorder = new FfmpegRecorder(new RecordingOptions(binary, temporary, 64, 64, 30, 1,
                "libx264", null, true));
        try {
            recorder.start();
            recorder.submitFrame(redTopBlueBottom(), System.nanoTime());
            Thread.sleep(1_150);
            Path video = recorder.finish().get(30, TimeUnit.SECONDS);
            assertTrue(Files.isRegularFile(video));
            assertTrue(Files.readString(recorder.recoveryDirectory().resolve("ffprobe.json")).contains("aac"));
            assertTrue(Files.readString(recorder.recoveryDirectory().resolve("audio.json")).contains("firstSampleNanos"));
            assertNull(recorder.failure());
        } finally { recorder.close(); }
    }

    @Test void windowsProcessCaptureCapturesGeneratedToneOnlyWithExplicitAudiblePermission() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("WACAMERA_ALLOW_AUDIBLE_TEST")),
                "Audible playback is disabled. Explicit WACAMERA_ALLOW_AUDIBLE_TEST=true is required.");
        Path binary = ffmpeg();
        assumeTrue("true".equalsIgnoreCase(System.getenv("WACAMERA_TEST_WINDOWS_AUDIO")),
                "Set WACAMERA_TEST_WINDOWS_AUDIO=true to explicitly enable Windows process audio capture");
        FfmpegRecorder recorder = new FfmpegRecorder(new RecordingOptions(binary, temporary, 64, 64, 30, 1,
                "libx264", null, true));
        Path video;
        // Only this test JVM's generated tone is captured, never an existing user application.
        AudioFormat format = new AudioFormat(48_000, 16, 2, true, false);
        try (SourceDataLine playback = AudioSystem.getSourceDataLine(format)) {
            playback.open(format, 48_000);
            recorder.start();
            recorder.submitFrame(redTopBlueBottom(), System.nanoTime());
            playback.start();
            byte[] tone = new byte[48_000 * 4 * 3 / 4];
            for (int i = 0; i < tone.length / 4; i++) {
                short sample = (short) (2500 * Math.sin(i * 2 * Math.PI * 440 / 48_000));
                tone[i * 4] = tone[i * 4 + 2] = (byte) sample;
                tone[i * 4 + 1] = tone[i * 4 + 3] = (byte) (sample >>> 8);
            }
            playback.write(tone, 0, tone.length);
            playback.drain();
            long remaining = 1_150_000_000L - (System.nanoTime() - recorder.startNanos());
            if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
            video = recorder.finish().get(30, TimeUnit.SECONDS);
        } finally { recorder.close(); }
        assertTrue(Files.isRegularFile(video));
        assertTrue(Files.readString(recorder.recoveryDirectory().resolve("ffprobe.json")).contains("aac"));
        assertNull(recorder.failure());
        Path decoded = temporary.resolve("audio.pcm");
        ProcessTools.run(List.of(binary.toString(), "-v", "error", "-nostdin", "-i", video.toString(), "-vn", "-f", "s16le", decoded.toString()),
                temporary.resolve("audio-decode.log"), temporary.resolve("audio-decode-error.log"), Duration.ofSeconds(10));
        byte[] samples = Files.readAllBytes(decoded);
        long amplitude = 0;
        for (int i = 0; i + 1 < samples.length; i += 2) amplitude += Math.abs((short) ((samples[i] & 255) | (samples[i + 1] << 8)));
        assertTrue(amplitude > 100_000, "The generated tone must be present, not merely an empty AAC track");
    }

    private static ByteBuffer redTopBlueBottom() {
        ByteBuffer buffer = ByteBuffer.allocate(64 * 64 * 4);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
            buffer.put((byte) (y < 32 ? 255 : 0)); buffer.put((byte) 0);
            buffer.put((byte) (y < 32 ? 0 : 255)); buffer.put((byte) 255);
        }
        return buffer.flip();
    }
}
