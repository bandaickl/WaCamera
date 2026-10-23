package dev.wacamera.recorder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bounded-memory real-time recorder. start() prepares external processes and must not run on the render thread.
 * submitFrame() immediately copies tightly-packed top-down RGBA8; callers retain and may reuse their buffer.
 * The first submitted frame establishes T0. finish() is idempotent and never blocks the calling thread.
 */
public final class FfmpegRecorder implements AutoCloseable {
    private static final long MAX_LAG_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long MIN_FREE_BYTES = 64L * 1024 * 1024;
    private static final int BUFFER_COUNT = 5;
    private static final int MAX_QUEUED_FRAMES = 3;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private record Frame(byte[] pixels, long slot) {}

    private final RecordingOptions options;
    private final CfrTimeline timeline;
    private final Object lock = new Object();
    private final ArrayDeque<byte[]> freeBuffers = new ArrayDeque<>();
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private final CompletableFuture<Path> completion = new CompletableFuture<>();
    private volatile boolean accepting;
    private volatile boolean started;
    private volatile boolean finishing;
    private volatile boolean firstFrameSeen;
    private volatile boolean workerDone;
    private volatile String failure;
    private volatile long startNanos;
    private volatile long framesWritten;
    private volatile long repeatedFrames;
    private volatile long droppedFrames;
    private volatile long stopFrameCount;
    private volatile long stoppedElapsedNanos;
    private volatile long lastProgressNanos;
    private long latestSubmittedSlot;
    private Frame initialFrame;
    private Path session;
    private Path ffmpeg;
    private Path ffprobe;
    private Path finalPath;
    private String actualEncoder;
    private Process encoderProcess;
    private AudioCapture audio;
    private SessionLock sessionLock;

    public FfmpegRecorder(RecordingOptions options) {
        this.options = options;
        this.timeline = new CfrTimeline(options.fps(), options.playbackSpeed());
    }

    /** Prepares the encoder and optional process audio capture; the clock remains at zero until the first frame. */
    public void start() throws IOException {
        synchronized (lock) {
            if (started || finishing) throw new IllegalStateException("Recorder can only be started once");
            started = true;
        }
        try {
            ffmpeg = ExecutableResolver.resolveFfmpeg(options.ffmpeg());
            ffprobe = ExecutableResolver.resolveFfprobe(ffmpeg);
            Path output = options.outputDirectory().toAbsolutePath().normalize();
            Files.createDirectories(output);
            ensureSpace(output, MIN_FREE_BYTES);
            String id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + UUID.randomUUID().toString().substring(0, 8);
            session = output.resolve(".wacamera-recovery").resolve(id);
            Files.createDirectories(session);
            sessionLock = SessionLock.acquire(session);
            finalPath = output.resolve("WaCamera-" + id + ".mp4");
            actualEncoder = probeEncoder(options.encoder());
            writeSessionMetadata("prepared");
            for (int i = 0; i < BUFFER_COUNT; i++) freeBuffers.add(new byte[options.frameBytes()]);
            encoderProcess = new ProcessBuilder(encoderCommand())
                    .redirectOutput(session.resolve("encoder-stdout.log").toFile())
                    .redirectError(session.resolve("encoder-stderr.log").toFile()).start();
            if (options.recordAudio()) {
                audio = new AudioCapture(session);
                audio.start();
            }
            synchronized (lock) {
                if (finishing) throw new IOException("Recording was cancelled during preparation");
                accepting = true;
                lastProgressNanos = System.nanoTime();
            }
            startDaemon("WaCamera video writer", this::writeRecording);
            startDaemon("WaCamera recorder watchdog", this::watchdog);
        } catch (Exception e) {
            setFailure(e);
            accepting = false;
            finishing = true;
            workerDone = true;
            // Finish session metadata while recovery/deletion is still excluded by our lease.
            try { writeSessionMetadata("failed"); } catch (IOException ignored) {}
            destroyProcesses();
            synchronized (lock) { freeBuffers.clear(); frames.clear(); }
            IOException error = recoveryException(e);
            completion.completeExceptionally(error);
            throw error;
        }
    }

    /** Copies bytes during this call. No file, process, or OpenGL operations occur here. */
    public void submitFrame(ByteBuffer rgba, long captureNanos) {
        if (!accepting || failure != null) return;
        if (rgba.remaining() != options.frameBytes()) throw new IllegalArgumentException("Expected exactly " + options.frameBytes() + " RGBA bytes");
        synchronized (lock) {
            if (!accepting || failure != null) return;
            long slot = firstFrameSeen ? timeline.nearestSlot(captureNanos - startNanos) : 0;
            if (firstFrameSeen && (captureNanos < startNanos || slot <= latestSubmittedSlot)) return;
            if (slot >= options.maximumFrames()) return;
            byte[] pixels = freeBuffers.pollFirst();
            if (pixels == null && !frames.isEmpty()) {
                pixels = frames.removeFirst().pixels();
                droppedFrames++;
            }
            if (pixels == null) { droppedFrames++; return; }
            rgba.duplicate().get(pixels);
            if (!firstFrameSeen) {
                startNanos = captureNanos;
                initialFrame = new Frame(pixels, 0);
                firstFrameSeen = true;
            } else {
                while (frames.size() >= MAX_QUEUED_FRAMES) {
                    freeBuffers.addLast(frames.removeFirst().pixels());
                    droppedFrames++;
                }
                frames.addLast(new Frame(pixels, slot));
            }
            latestSubmittedSlot = slot;
            lock.notifyAll();
        }
    }

    public CompletableFuture<Path> finish() {
        synchronized (lock) {
            if (!finishing) {
                accepting = false;
                stoppedElapsedNanos = elapsedNanos(System.nanoTime());
                stopFrameCount = firstFrameSeen
                        ? Math.min(options.maximumFrames(), Math.max(1, timeline.framesForDuration(stoppedElapsedNanos))) : 0;
                finishing = true;
                lock.notifyAll();
                if (!started) {
                    workerDone = true;
                    completion.completeExceptionally(new IOException("Recording was not started"));
                }
            }
        }
        return completion;
    }

    public boolean isRecording() { return accepting && failure == null; }
    public String failure() { return failure; }
    /** Zero until the first frame has been accepted. */
    public long startNanos() { return firstFrameSeen ? startNanos : 0; }
    public double elapsedSeconds() {
        if (!firstFrameSeen) return 0;
        return (finishing ? stoppedElapsedNanos : elapsedNanos(System.nanoTime())) / 1_000_000_000.0;
    }
    private long elapsedNanos(long now) {
        return firstFrameSeen ? Math.min((long) (options.durationSeconds() * 1_000_000_000.0), Math.max(0, now - startNanos)) : 0;
    }
    public int width() { return options.width(); }
    public int height() { return options.height(); }
    public int fps() { return options.fps(); }
    public double playbackSpeed() { return options.playbackSpeed(); }
    public double captureFps() { return options.captureFps(); }
    public Path recoveryDirectory() { return session; }
    public long framesWritten() { return framesWritten; }
    public long repeatedFrames() { return repeatedFrames; }
    public long droppedFrames() { return droppedFrames; }

    private void writeRecording() {
        Frame current = null;
        try (OutputStream pipe = new BufferedOutputStream(encoderProcess.getOutputStream(), 64 * 1024)) {
            synchronized (lock) {
                while (!firstFrameSeen && !finishing && failure == null) lock.wait(100);
                if (!firstFrameSeen) throw new IOException("No video frame was captured");
                current = initialFrame;
                initialFrame = null;
            }
            writeSessionMetadata("recording");
            long settle = Math.max(TimeUnit.MILLISECONDS.toNanos(35), 2_000_000_000L / options.fps());
            if (options.playbackSpeed() > 1) {
                // A sparse camera pass can itself take longer than two output-frame periods.
                // Allow its asynchronous readback to arrive before committing an older image,
                // but keep added latency below the real-time watchdog's two-second budget.
                settle = Math.max(settle, Math.min(TimeUnit.SECONDS.toNanos(1),
                        Math.round(2_000_000_000.0 * options.playbackSpeed() / options.fps())));
            }
            while (failure == null) {
                long now = System.nanoTime();
                if (!finishing && now - startNanos >= (long) (options.durationSeconds() * 1_000_000_000.0)) {
                    synchronized (lock) {
                        if (!finishing) {
                            accepting = false;
                            stoppedElapsedNanos = (long) (options.durationSeconds() * 1_000_000_000.0);
                            stopFrameCount = options.maximumFrames();
                            finishing = true;
                        }
                    }
                }
                long available = finishing ? stopFrameCount
                        : Math.min(options.maximumFrames(), timeline.readyFrames(now - startNanos, settle));
                if (framesWritten >= available) {
                    if (finishing) break;
                    synchronized (lock) { lock.wait(5); }
                    continue;
                }
                boolean updated = false;
                synchronized (lock) {
                    while (!frames.isEmpty() && frames.peekFirst().slot() <= framesWritten) {
                        freeBuffers.addLast(current.pixels());
                        current = frames.removeFirst();
                        updated = true;
                    }
                }
                if (!updated && framesWritten > 0) repeatedFrames++;
                pipe.write(current.pixels());
                framesWritten++;
                lastProgressNanos = System.nanoTime();
            }
            pipe.flush();
        } catch (Exception e) {
            setFailure(e);
        } finally {
            synchronized (lock) {
                accepting = false;
                if (!finishing) stoppedElapsedNanos = elapsedNanos(System.nanoTime());
                finishing = true;
                frames.clear(); freeBuffers.clear(); initialFrame = null;
            }
            finishOnWorker();
        }
    }

    private void finishOnWorker() {
        try {
            AudioCapture.Metadata metadata = audio == null ? null : audio.stop();
            if (!encoderProcess.waitFor(15, TimeUnit.SECONDS)) {
                encoderProcess.destroyForcibly();
                throw new IOException("Video encoder did not stop within 15 seconds");
            }
            if (encoderProcess.exitValue() != 0) throw new IOException("Video encoder failed; see encoder-stderr.log");
            if (failure != null) throw new IOException(failure);
            if (framesWritten == 0) throw new IOException("No frames were encoded");
            writeSessionMetadata("finalizing");
            Path candidate = session.resolve("completed.mp4");
            ProcessTools.run(finalizeCommand(candidate, metadata), session.resolve("finalize-stdout.log"),
                    session.resolve("finalize-stderr.log"), Duration.ofMinutes(5));
            validate(candidate);
            try { Files.move(candidate, finalPath, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(candidate, finalPath); }
            try { writeSessionMetadata("complete"); } catch (IOException ignored) { /* The validated MP4 has already been committed. */ }
            // Logs/metadata remain useful; the large temporary media is removed only after validation and commit.
            try {
                Files.deleteIfExists(session.resolve("video.fragmented.mp4"));
                Files.deleteIfExists(session.resolve("audio.wav"));
            } catch (IOException ignored) { /* A successful recording remains successful if cleanup is unavailable. */ }
            completion.complete(finalPath);
        } catch (Exception e) {
            setFailure(e);
            try { writeSessionMetadata("failed"); } catch (IOException ignored) {}
            completion.completeExceptionally(recoveryException(e));
        } finally {
            workerDone = true;
            destroyProcesses();
        }
    }

    private void watchdog() {
        long nextSpaceCheck = 0;
        while (!workerDone && failure == null) {
            try {
                Thread.sleep(100);
                long now = System.nanoTime();
                if (accepting && !encoderProcess.isAlive()) throw new IOException("Video encoder terminated unexpectedly");
                if (accepting && audio != null && !audio.isAlive()) throw new IOException(audio.failureMessage());
                if (firstFrameSeen && !finishing) {
                    double writtenSeconds = timeline.captureSecondsForFrames(framesWritten);
                    if (now - startNanos - (long) (writtenSeconds * 1_000_000_000.0) > MAX_LAG_NANOS) {
                        throw new IOException("Recording could not keep up for 2 seconds; use a lower recording quality");
                    }
                }
                if (firstFrameSeen && finishing && encoderProcess.isAlive() && now - lastProgressNanos > TimeUnit.SECONDS.toNanos(15)) {
                    throw new IOException("Video encoder stopped accepting frames");
                }
                if (accepting && now >= nextSpaceCheck) {
                    ensureSpace(session, MIN_FREE_BYTES);
                    nextSpaceCheck = now + TimeUnit.SECONDS.toNanos(2);
                }
            } catch (Exception e) {
                setFailure(e);
                synchronized (lock) {
                    accepting = false;
                    if (!finishing) stoppedElapsedNanos = elapsedNanos(System.nanoTime());
                    stopFrameCount = framesWritten;
                    finishing = true;
                    lock.notifyAll();
                }
                if (encoderProcess != null) encoderProcess.destroyForcibly();
            }
        }
    }

    private String probeEncoder(String requested) throws IOException {
        try { runEncoderProbe(requested); return requested; }
        catch (IOException e) {
            if (requested.equals("libx264")) throw e;
            runEncoderProbe("libx264");
            return "libx264";
        }
    }

    private void runEncoderProbe(String encoder) throws IOException {
        List<String> args = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin", "-f", "lavfi", "-i",
                "color=c=black:s=" + options.width() + "x" + options.height() + ":r=30", "-frames:v", "2", "-an", "-c:v", encoder, "-pix_fmt", "yuv420p", "-f", "null", "-"));
        ProcessTools.run(args, session.resolve("probe-" + encoder + "-stdout.log"),
                session.resolve("probe-" + encoder + "-stderr.log"), Duration.ofSeconds(15));
    }

    private List<String> encoderCommand() {
        List<String> command = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "warning", "-nostdin", "-n",
                "-f", "rawvideo", "-pixel_format", "rgba", "-video_size", options.width() + "x" + options.height(),
                "-framerate", Integer.toString(options.fps()), "-i", "pipe:0", "-an", "-c:v", actualEncoder));
        if (actualEncoder.equals("libx264")) command.addAll(List.of("-preset", "veryfast", "-crf", "20"));
        else command.addAll(List.of("-b:v", "12M"));
        command.addAll(List.of("-vf", "scale=in_range=pc:out_range=tv:out_color_matrix=bt709", "-colorspace", "bt709", "-color_range", "tv",
                "-pix_fmt", "yuv420p", "-g", Integer.toString(options.fps() * 2),
                "-movflags", "+frag_keyframe+empty_moov+default_base_moof", "-frag_duration", "1000000",
                session.resolve("video.fragmented.mp4").toString()));
        return command;
    }

    private List<String> finalizeCommand(Path target, AudioCapture.Metadata audioMetadata) throws IOException {
        return RecordingFinalizer.command(ffmpeg, session, target, videoMetadata(), audioMetadata);
    }

    private void validate(Path file) throws IOException {
        RecordingFinalizer.validate(ffprobe, file, session, videoMetadata(), options.recordAudio());
    }

    private RecordingFinalizer.Video videoMetadata() {
        return new RecordingFinalizer.Video(options.width(), options.height(), options.fps(), framesWritten, startNanos);
    }

    private void writeSessionMetadata(String status) throws IOException {
        if (session == null) return;
        JsonObject data = new JsonObject();
        data.addProperty("schemaVersion", 1);
        data.addProperty("status", status);
        data.addProperty("width", options.width()); data.addProperty("height", options.height());
        data.addProperty("fps", options.fps()); data.addProperty("requestedDurationSeconds", options.durationSeconds());
        data.addProperty("playbackSpeed", options.playbackSpeed()); data.addProperty("captureFps", options.captureFps());
        data.addProperty("elapsedSeconds", elapsedSeconds());
        data.addProperty("requestedVideoDurationSeconds", options.maximumFrames() / (double) options.fps());
        data.addProperty("videoDurationSeconds", timeline.secondsForFrames(framesWritten));
        data.addProperty("encoder", actualEncoder); data.addProperty("recordAudio", options.recordAudio());
        data.addProperty("startNanos", startNanos()); data.addProperty("framesWritten", framesWritten);
        data.addProperty("repeatedFrames", repeatedFrames); data.addProperty("droppedFrames", droppedFrames);
        data.addProperty("output", finalPath == null ? "" : finalPath.toString());
        data.addProperty("failure", failure);
        Path temporary = session.resolve("session.json.tmp");
        Files.writeString(temporary, GSON.toJson(data), StandardCharsets.UTF_8);
        try { Files.move(temporary, session.resolve("session.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, session.resolve("session.json"), StandardCopyOption.REPLACE_EXISTING); }
    }

    private void setFailure(Throwable throwable) {
        if (failure == null) {
            synchronized (lock) {
                if (failure == null) failure = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
                lock.notifyAll();
            }
        }
    }

    private IOException recoveryException(Throwable cause) {
        return new IOException((failure == null ? "Recording failed" : failure)
                + (session == null ? "" : "; recovery files: " + session), cause);
    }

    private void destroyProcesses() {
        if (audio != null) audio.close();
        if (encoderProcess != null && encoderProcess.isAlive()) encoderProcess.destroyForcibly();
        if (sessionLock != null) {
            SessionLock lease = sessionLock;
            sessionLock = null;
            // Keep recovery/deletion excluded until the COM owner has finished all writes and cleanup.
            if (audio != null) audio.afterTermination(lease::close);
            else lease.close();
        }
    }

    private static void ensureSpace(Path path, long required) throws IOException {
        if (Files.getFileStore(path).getUsableSpace() < required) throw new IOException("Not enough disk space for recording/finalization");
    }

    private static void startDaemon(String name, Runnable action) {
        Thread thread = new Thread(action, name); thread.setDaemon(true); thread.start();
    }

    /** Requests orderly finalization; it does not wait on the render thread. */
    @Override public void close() { finish(); }
}
