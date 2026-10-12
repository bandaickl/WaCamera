package dev.wacamera.recorder;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Audio fixtures are written to files and decoded. No playback device is opened. */
class RecordingRecoveryTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path temporary;

    private Path ffmpeg() {
        String configured = System.getenv("WACAMERA_TEST_FFMPEG");
        assumeTrue(configured != null && Files.isRegularFile(Path.of(configured)));
        return Path.of(configured);
    }

    @Test void recoversActualPacketCountAndPreservesOriginals() throws Exception {
        Path binary = ffmpeg();
        Path session = fixture(binary, false);
        assertEquals(1, RecordingRecovery.listRecoverySessions(temporary).size());
        Path recovered = RecordingRecovery.recoverFromSession(session, binary).get(20, TimeUnit.SECONDS);
        assertTrue(Files.isRegularFile(recovered));
        assertTrue(Files.isRegularFile(session.resolve("video.fragmented.mp4")));
        assertTrue(RecordingRecovery.listRecoverySessions(temporary).isEmpty());
        JsonObject metadata = new Gson().fromJson(Files.readString(session.resolve("session.json")), JsonObject.class);
        assertEquals(120, metadata.get("recoveredFrames").getAsLong());
        Path second = RecordingRecovery.recoverFromSession(session, binary).get(20, TimeUnit.SECONDS);
        assertNotEquals(recovered, second);
        assertTrue(Files.isRegularFile(recovered));
    }

    @Test void missingAudioRequiresExplicitSilentRecovery() throws Exception {
        Path binary = ffmpeg();
        Path session = fixture(binary, true);
        assertThrows(ExecutionException.class, () -> RecordingRecovery.recoverFromSession(session, binary).get(20, TimeUnit.SECONDS));
        Path recovered = RecordingRecovery.recoverSilentFromSession(session, binary).get(20, TimeUnit.SECONDS);
        assertTrue(recovered.getFileName().toString().contains("-silent"));
        assertTrue(Files.isRegularFile(session.resolve("video.fragmented.mp4")));
    }

    @Test void activeSessionCannotBeRecovered() throws Exception {
        Path binary = ffmpeg();
        Path session = fixture(binary, false);
        try (SessionLock ignored = SessionLock.acquire(session)) {
            assertTrue(RecordingRecovery.listRecoverySessions(temporary).get(0).active());
            assertThrows(ExecutionException.class, () -> RecordingRecovery.recoverFromSession(session, binary).get(20, TimeUnit.SECONDS));
        }
        assertFalse(RecordingRecovery.listRecoverySessions(temporary).get(0).active());
    }

    @Test void synthesisedPcmPulseOffsetsAndDriftAreCorrectedWithoutPlayback() throws Exception {
        Path binary = ffmpeg();
        Path session = fixture(binary, true);
        // Native clock is 90 seconds behind Java. Audio leads video by 200ms.
        // 192192 frames in 4 real seconds simulate a 1000ppm faster sample clock.
        int frameCount = 192_192;
        byte[] pcm = new byte[frameCount * 4];
        for (double target : new double[]{0.5, 3.5}) {
            int from = (int) Math.round((target + 0.2) * 1.001 * 48_000);
            for (int i = from; i < from + 2400 && i < frameCount; i++) {
                short value = (short) (10_000 * Math.sin((i - from) * 2 * Math.PI * 600 / 48_000));
                pcm[i * 4] = pcm[i * 4 + 2] = (byte) value;
                pcm[i * 4 + 1] = pcm[i * 4 + 3] = (byte) (value >>> 8);
            }
        }
        try (AudioInputStream input = new AudioInputStream(new ByteArrayInputStream(pcm), new AudioFormat(48_000, 16, 2, true, false), frameCount)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, session.resolve("audio.wav").toFile());
        }
        Files.writeString(session.resolve("audio.json"), "{\"sampleRate\":48000,\"channels\":2,\"totalFrames\":192192,\"firstSampleNanos\":9800000000,\"lastSampleEndNanos\":13800000000}");
        Files.writeString(session.resolve("audio-clock.json"), "{\"nativeToJavaNanos\":90000000000,\"uncertaintyNanos\":1000}");
        Path recovered = RecordingRecovery.recoverFromSession(session, binary).get(20, TimeUnit.SECONDS);
        Path decoded = temporary.resolve("pulse-samples.s16le");
        ProcessTools.run(List.of(binary.toString(), "-v", "error", "-nostdin", "-i", recovered.toString(), "-vn", "-ac", "1", "-ar", "48000",
                "-f", "s16le", decoded.toString()), temporary.resolve("decode.log"), temporary.resolve("decode-errors.log"), Duration.ofSeconds(10));
        byte[] samples = Files.readAllBytes(decoded);
        List<Double> pulses = new ArrayList<>();
        boolean inside = false;
        for (int block = 0; block + 960 <= samples.length; block += 960) {
            long energy = 0;
            for (int i = block; i < block + 960; i += 2) energy += Math.abs((short) ((samples[i] & 255) | (samples[i + 1] << 8)));
            boolean loud = energy / 480 > 1_000;
            if (loud && !inside) pulses.add(block / 2.0 / 48_000);
            inside = loud;
        }
        assertEquals(2, pulses.size());
        assertEquals(0.5, pulses.get(0), 0.05);
        assertEquals(3.5, pulses.get(1), 0.05);
        assertTrue(Files.isRegularFile(session.resolve("audio.wav")));
    }

    private Path fixture(Path binary, boolean audioRequested) throws Exception {
        Path session = temporary.resolve(".wacamera-recovery").resolve("中断した 録画");
        Files.createDirectories(session);
        ProcessTools.run(List.of(binary.toString(), "-v", "error", "-nostdin", "-f", "lavfi", "-i", "color=c=blue:s=64x64:r=30:d=4",
                "-an", "-c:v", "libx264", "-preset", "ultrafast", "-g", "30", "-pix_fmt", "yuv420p", "-movflags",
                "+frag_keyframe+empty_moov+default_base_moof", session.resolve("video.fragmented.mp4").toString()),
                session.resolve("fixture.log"), session.resolve("fixture-errors.log"), Duration.ofSeconds(15));
        JsonObject metadata = new JsonObject();
        metadata.addProperty("schemaVersion", 1); metadata.addProperty("status", "recording");
        metadata.addProperty("width", 64); metadata.addProperty("height", 64); metadata.addProperty("fps", 30);
        metadata.addProperty("framesWritten", 0); // Stale metadata from an interrupted recording.
        metadata.addProperty("startNanos", 100_000_000_000L);
        metadata.addProperty("recordAudio", audioRequested);
        Files.writeString(session.resolve("session.json"), new Gson().toJson(metadata));
        return session;
    }
}
