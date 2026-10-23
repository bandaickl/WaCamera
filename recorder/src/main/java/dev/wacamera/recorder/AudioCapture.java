package dev.wacamera.recorder;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** In-process Windows process-loopback capture. Every COM call belongs to one audio worker. */
final class AudioCapture implements AutoCloseable {
    record Metadata(int sampleRate, int channels, long totalFrames, long firstSampleNanos,
                    long lastSampleEndNanos, long nativeToJavaNanos, long clockUncertaintyNanos) {
        double spanSeconds() { return (lastSampleEndNanos - firstSampleNanos) / 1_000_000_000.0; }
        double tempoRatio() { return ((double) totalFrames / sampleRate) / spanSeconds(); }
        double offsetSeconds(long videoStart) {
            return ((firstSampleNanos - videoStart) + nativeToJavaNanos) / 1_000_000_000.0;
        }
    }

    private static final Gson GSON = new Gson();
    private static final AtomicReference<AudioCapture> ACTIVE = new AtomicReference<>();
    private static final long MAX_CAPTURE_NANOS = TimeUnit.SECONDS.toNanos(3610);
    private final Path session;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Metadata> completed = new CompletableFuture<>();
    private volatile boolean stopRequested;
    private volatile IOException failure;
    private volatile Thread worker;
    private long nativeToJavaNanos;
    private long uncertainty;

    AudioCapture(Path session) { this.session = session; }

    /** Retained for older package-local callers; no executable is used or started. */
    @Deprecated AudioCapture(Path ignoredHelper, Path session) { this(session); }

    void start() throws IOException {
        synchronized (this) {
            if (worker != null || stopRequested) throw new IOException("Audio capture can only be started once");
            // Do this before loading the optional Windows/JNA implementation.
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            if (!os.startsWith("windows") || !(arch.equals("amd64") || arch.equals("x86_64")))
                throw new IOException("Game audio recording requires Windows 11 x64");
            if (!ACTIVE.compareAndSet(null, this))
                throw new IOException("The previous audio capture is still stopping; wait before recording again");
            worker = new Thread(this::captureOnWorker, "WaCamera process audio");
            worker.setDaemon(true);
            try { worker.start(); }
            catch (RuntimeException | Error error) {
                worker = null;
                ACTIVE.compareAndSet(this, null);
                throw asIOException(error);
            }
        }
        try { await(ready, 25, "Audio capture preparation"); }
        catch (IOException error) { close(); throw error; }
    }

    boolean isAlive() { return worker != null && !completed.isDone() && failure == null; }
    String failureMessage() { return failure == null ? "Audio capture terminated unexpectedly" : failure.getMessage(); }

    Metadata stop() throws IOException {
        stopRequested = true;
        if (worker == null) throw new IOException("Audio capture was not started");
        return await(completed, 20, "Audio capture stop");
    }

    private void captureOnWorker() {
        WindowsProcessLoopback capture = null;
        PcmWaveWriter wave = null;
        AudioTimeline timeline = null;
        boolean captureStarted = false;
        IOException problem = null;
        Metadata metadata = null;
        try {
            if (stopRequested) throw new IOException("Audio preparation was cancelled");
            capture = WindowsProcessLoopback.open();
            if (stopRequested) throw new IOException("Audio preparation was cancelled");
            measureClock();
            wave = new PcmWaveWriter(session.resolve("audio.wav"));
            if (stopRequested) throw new IOException("Audio preparation was cancelled");
            capture.start();
            captureStarted = true;
            long started = WindowsProcessLoopback.qpcNanos();
            timeline = new AudioTimeline(wave, started);
            ready.complete(null);
            long nextCheckpoint = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!stopRequested) {
                capture.awaitSamples(100);
                drain(capture, timeline);
                if (WindowsProcessLoopback.qpcNanos() - started > MAX_CAPTURE_NANOS)
                    throw new IOException("Audio capture time limit reached");
                if (System.nanoTime() >= nextCheckpoint) {
                    saveProgress(wave, timeline);
                    nextCheckpoint = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                }
            }
        } catch (Throwable error) {
            problem = asIOException(error);
            failure = problem;
        } finally {
            // Never call COM from stop()/close(): release and all final reads stay on this thread.
            if (captureStarted) {
                boolean deviceStopped = false;
                try { capture.stop(); deviceStopped = true; }
                catch (Throwable error) { problem = combine(problem, error); }
                if (timeline != null) {
                    if (deviceStopped) {
                        try {
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                            while (drain(capture, timeline)) {
                                if (System.nanoTime() >= deadline)
                                    throw new IOException("Audio packets did not drain after stopping");
                            }
                        } catch (Throwable error) { problem = combine(problem, error); }
                    }
                    try { timeline.finish(WindowsProcessLoopback.qpcNanos()); }
                    catch (Throwable error) { problem = combine(problem, error); }
                }
            }
            if (wave != null) {
                try {
                    if (timeline != null) saveProgress(wave, timeline);
                    else wave.checkpoint();
                } catch (Throwable error) { problem = combine(problem, error); }
                try { wave.close(); }
                catch (IOException error) { problem = combine(problem, error); }
            }
            if (capture != null) {
                try { capture.close(); }
                catch (Throwable error) { problem = combine(problem, error); }
            }
            if (problem == null) {
                try { metadata = readMetadata(session, nativeToJavaNanos, uncertainty); }
                catch (IOException error) { problem = error; }
            }
            if (problem != null) {
                failure = problem;
                // The WAV and compatible clock/metadata remain available for explicit recovery.
                try { Files.writeString(session.resolve("audio-error.log"), problem.toString(), StandardCharsets.UTF_8); }
                catch (IOException ignored) {}
            }
            // No device, WAV or metadata writes remain past this point.
            ACTIVE.compareAndSet(this, null);
            if (problem != null) {
                ready.completeExceptionally(problem);
                completed.completeExceptionally(problem);
            } else {
                ready.complete(null);
                completed.complete(metadata);
            }
        }
    }

    private static boolean drain(WindowsProcessLoopback capture, AudioTimeline timeline) throws IOException {
        // Bound each drain so stop/cancel and the overall duration limit are observed
        // even if a slow disk lets fresh audio arrive as quickly as packets are read.
        for (int count = 0; count < 64; count++) {
            WindowsProcessLoopback.Packet packet = capture.readPacket();
            if (packet == null) return false;
            timeline.accept(packet, WindowsProcessLoopback.qpcNanos());
        }
        return true;
    }

    private void measureClock() throws IOException {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            long before = System.nanoTime();
            long nativeNanos = WindowsProcessLoopback.qpcNanos();
            long after = System.nanoTime();
            long roundTrip = after - before;
            if (roundTrip < best) {
                best = roundTrip;
                nativeToJavaNanos = before + roundTrip / 2 - nativeNanos;
                uncertainty = roundTrip / 2;
            }
        }
        if (uncertainty > 25_000_000L) throw new IOException("Audio clock measurement was too slow; retry recording");
        JsonObject clock = new JsonObject();
        clock.addProperty("nativeToJavaNanos", nativeToJavaNanos);
        clock.addProperty("uncertaintyNanos", uncertainty);
        writeJson("audio-clock.json", clock);
    }

    private void saveProgress(PcmWaveWriter wave, AudioTimeline timeline) throws IOException {
        wave.checkpoint();
        if (wave.frames() <= 0) return;
        JsonObject data = new JsonObject();
        data.addProperty("sampleRate", PcmWaveWriter.RATE);
        data.addProperty("channels", PcmWaveWriter.CHANNELS);
        data.addProperty("totalFrames", wave.frames());
        data.addProperty("firstSampleNanos", timeline.firstSampleNanos());
        data.addProperty("lastSampleEndNanos", timeline.lastSampleEndNanos());
        data.addProperty("timestampEstimated", timeline.timestampEstimated());
        writeJson("audio.json", data);
    }

    private void writeJson(String name, JsonObject value) throws IOException {
        Path target = session.resolve(name);
        Path temporary = session.resolve(name + ".tmp");
        Files.writeString(temporary, GSON.toJson(value), StandardCharsets.UTF_8);
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    static Metadata readMetadata(Path session) throws IOException {
        try {
            JsonObject clock = GSON.fromJson(Files.readString(session.resolve("audio-clock.json")), JsonObject.class);
            return readMetadata(session, clock.get("nativeToJavaNanos").getAsLong(), clock.get("uncertaintyNanos").getAsLong());
        } catch (RuntimeException e) { throw new IOException("Could not read audio clock mapping", e); }
    }

    private static Metadata readMetadata(Path session, long nativeToJavaNanos, long uncertainty) throws IOException {
        try {
            JsonObject json = GSON.fromJson(Files.readString(session.resolve("audio.json")), JsonObject.class);
            Metadata metadata = new Metadata(json.get("sampleRate").getAsInt(), json.get("channels").getAsInt(),
                    json.get("totalFrames").getAsLong(), json.get("firstSampleNanos").getAsLong(),
                    json.get("lastSampleEndNanos").getAsLong(), nativeToJavaNanos, uncertainty);
            if (metadata.sampleRate != 48_000 || metadata.channels != 2 || metadata.totalFrames <= 0
                    || metadata.lastSampleEndNanos <= metadata.firstSampleNanos || !Files.isRegularFile(session.resolve("audio.wav"))) {
                throw new IOException("Audio capture produced invalid audio metadata");
            }
            if (uncertainty < 0 || uncertainty > 25_000_000L) throw new IOException("Invalid or imprecise audio clock mapping");
            // A larger difference indicates missing/discontinuous samples, not ordinary device clock drift.
            if (!Double.isFinite(metadata.tempoRatio()) || Math.abs(metadata.tempoRatio() - 1.0) > 0.02) {
                throw new IOException("Audio capture clock/sample mismatch exceeds 2%; recovery data was retained");
            }
            return metadata;
        } catch (RuntimeException e) { throw new IOException("Could not read audio metadata", e); }
    }

    private static IOException asIOException(Throwable error) {
        return error instanceof IOException io ? io : new IOException("Windows process audio capture failed: " + error, error);
    }

    private static IOException combine(IOException previous, Throwable error) {
        IOException next = asIOException(error);
        if (previous == null) return next;
        if (previous != next) previous.addSuppressed(next);
        return previous;
    }

    private static <T> T await(CompletableFuture<T> future, int seconds, String operation) throws IOException {
        try { return future.get(seconds, TimeUnit.SECONDS); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(operation + " was interrupted", error);
        } catch (ExecutionException error) { throw asIOException(error.getCause()); }
        catch (TimeoutException error) { throw new IOException(operation + " timed out; recovery files were retained", error); }
    }

    void afterTermination(Runnable action) {
        if (worker == null) action.run();
        else completed.whenComplete((metadata, error) -> action.run());
    }

    @Override public void close() {
        stopRequested = true;
        if (worker == null) return;
        try { await(completed, 20, "Audio capture cleanup"); }
        catch (IOException ignored) { /* No unsafe thread termination; the worker still owns its resources. */ }
    }
}
