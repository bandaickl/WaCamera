package dev.wacamera.recorder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** Explicit recovery of interrupted local sessions. Original media is always retained. */
public final class RecordingRecovery {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private RecordingRecovery() {}

    public record RecoverySession(Path directory, String status, boolean audioRequested,
                                  boolean audioFilesPresent, boolean active, String error) {}

    /** Lists unfinished sessions. Does not launch any encoder or modify their metadata. */
    public static List<RecoverySession> listRecoverySessions(Path outputDirectory) throws IOException {
        Path root = outputDirectory.toAbsolutePath().normalize().resolve(".wacamera-recovery");
        if (!Files.isDirectory(root)) return List.of();
        List<RecoverySession> sessions = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            for (Path directory : entries.sorted(Comparator.reverseOrder()).toList()) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                String status = "unknown", error = null;
                boolean requested = false;
                try {
                    JsonObject data = readJson(directory.resolve("session.json"));
                    status = data.has("status") ? data.get("status").getAsString() : "unknown";
                    if (status.equals("complete") || status.startsWith("recovered")) continue;
                    requested = audioRequested(data);
                    if (data.has("failure") && !data.get("failure").isJsonNull()) error = data.get("failure").getAsString();
                    if (!Files.isRegularFile(directory.resolve("video.fragmented.mp4"))) error = "No recoverable video fragment";
                } catch (Exception e) { error = e.getMessage(); }
                boolean audioPresent = Files.isRegularFile(directory.resolve("audio.wav"))
                        && Files.isRegularFile(directory.resolve("audio.json")) && Files.isRegularFile(directory.resolve("audio-clock.json"));
                sessions.add(new RecoverySession(directory, status, requested, audioPresent, SessionLock.isActive(directory), error));
            }
        }
        return List.copyOf(sessions);
    }

    /** Preserves the session's audio choice. Missing/invalid requested audio is an error, never silent fallback. */
    public static CompletableFuture<Path> recoverFromSession(Path session, Path ffmpeg) {
        return recoverAsync(session, ffmpeg, false);
    }

    /** An explicitly selected silent recovery. The resulting file is named with a -silent suffix. */
    public static CompletableFuture<Path> recoverSilentFromSession(Path session, Path ffmpeg) {
        return recoverAsync(session, ffmpeg, true);
    }

    private static CompletableFuture<Path> recoverAsync(Path directory, Path configuredFfmpeg, boolean silent) {
        CompletableFuture<Path> future = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try { future.complete(recover(directory, configuredFfmpeg, silent)); }
            catch (Exception e) { future.completeExceptionally(e); }
        }, "WaCamera recording recovery");
        worker.setDaemon(true);
        worker.start();
        return future;
    }

    private static Path recover(Path requested, Path configuredFfmpeg, boolean silent) throws IOException {
        Path session = requested.toAbsolutePath().normalize();
        if (session.getParent() == null || !session.getParent().getFileName().toString().equals(".wacamera-recovery")
                || Files.isSymbolicLink(session) || !Files.isDirectory(session)) throw new IOException("Not a WaCamera recovery session");
        try (SessionLock ignored = SessionLock.acquire(session)) {
            JsonObject metadata = readJson(session.resolve("session.json"));
            if (!metadata.has("schemaVersion") || metadata.get("schemaVersion").getAsInt() != 1) throw new IOException("Unsupported recovery metadata version");
            boolean audioRequested = audioRequested(metadata);
            Path ffmpeg = ExecutableResolver.resolveFfmpeg(configuredFfmpeg);
            Path ffprobe = ExecutableResolver.resolveFfprobe(ffmpeg);
            Path source = session.resolve("video.fragmented.mp4");
            if (!Files.isRegularFile(source) || Files.size(source) == 0) throw new IOException("No recoverable video fragment");
            String id = UUID.randomUUID().toString().substring(0, 8);
            Path attempt = session.resolve("recovery-" + id);
            Files.createDirectory(attempt);
            RecordingFinalizer.Video video = probeVideo(ffprobe, source, attempt, metadata);
            AudioCapture.Metadata audio = null;
            if (audioRequested && !silent) {
                if (!metadata.has("startNanos") || metadata.get("startNanos").getAsLong() == 0) {
                    throw new IOException("Audio/video start mapping is missing. Choose explicit silent recovery to recover video only.");
                }
                try { audio = AudioCapture.readMetadata(session); }
                catch (IOException e) { throw new IOException("Recorded audio cannot be aligned. Choose explicit silent recovery to recover video only.", e); }
            }
            Path candidate = attempt.resolve("completed.mp4");
            ProcessTools.run(RecordingFinalizer.command(ffmpeg, session, candidate, video, audio),
                    attempt.resolve("finalize-stdout.log"), attempt.resolve("finalize-stderr.log"), Duration.ofMinutes(5));
            RecordingFinalizer.validate(ffprobe, candidate, attempt, video, audio != null);
            Path output = session.getParent().getParent().resolve("WaCamera-recovered-" + session.getFileName() + "-" + id + (silent ? "-silent" : "") + ".mp4");
            try { Files.move(candidate, output, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(candidate, output); }
            metadata.addProperty("status", silent ? "recovered-silent" : "recovered");
            metadata.addProperty("recoveredOutput", output.toString());
            metadata.addProperty("recoveredFrames", video.frames());
            metadata.addProperty("recoveredVideoDurationSeconds", video.seconds());
            metadata.addProperty("recoveredAudio", audio != null);
            // Recovery has already succeeded if diagnostic metadata cannot be updated afterwards.
            try {
                Path updated = session.resolve("session-recovered.json.tmp");
                Files.writeString(updated, GSON.toJson(metadata));
                try { Files.move(updated, session.resolve("session.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(updated, session.resolve("session.json"), StandardCopyOption.REPLACE_EXISTING); }
            } catch (IOException ignoredWriteFailure) {}
            return output;
        } catch (RuntimeException e) { throw new IOException("Invalid recovery data in " + session, e); }
    }

    private static boolean audioRequested(JsonObject metadata) throws IOException {
        // Old sessions predate playbackSpeed. Fragments already have their final video timeline;
        // recovery must copy those timestamps, never divide their duration by the speed again.
        double speed = metadata.has("playbackSpeed") ? metadata.get("playbackSpeed").getAsDouble() : 1.0;
        if (!Double.isFinite(speed) || speed < 1 || speed > 120) throw new IOException("Invalid playback speed in recovery metadata");
        return speed == 1 && metadata.has("recordAudio") && metadata.get("recordAudio").getAsBoolean();
    }

    private static RecordingFinalizer.Video probeVideo(Path ffprobe, Path source, Path attempt, JsonObject metadata) throws IOException {
        Path report = attempt.resolve("source-probe.json");
        ProcessTools.run(List.of(ffprobe.toString(), "-v", "error", "-select_streams", "v:0", "-count_packets",
                "-show_entries", "stream=codec_name,width,height,nb_read_packets,avg_frame_rate", "-of", "json", source.toString()),
                report, attempt.resolve("source-probe-stderr.log"), Duration.ofMinutes(2));
        JsonObject json = readJson(report);
        for (JsonElement element : json.getAsJsonArray("streams")) {
            JsonObject stream = element.getAsJsonObject();
            if (!stream.get("codec_name").getAsString().equals("h264")) throw new IOException("Recovery source is not H.264");
            int width = metadata.get("width").getAsInt(), height = metadata.get("height").getAsInt(), fps = metadata.get("fps").getAsInt();
            if (stream.get("width").getAsInt() != width || stream.get("height").getAsInt() != height) throw new IOException("Video dimensions differ from saved metadata");
            String[] rate = stream.get("avg_frame_rate").getAsString().split("/");
            double measuredFps = Double.parseDouble(rate[0]) / Double.parseDouble(rate[1]);
            if (!Double.isFinite(measuredFps) || Math.abs(measuredFps - fps) > 0.01) throw new IOException("Video frame rate differs from saved metadata");
            long start = metadata.has("startNanos") ? metadata.get("startNanos").getAsLong() : 0;
            return new RecordingFinalizer.Video(width, height, fps, stream.get("nb_read_packets").getAsLong(), start);
        }
        throw new IOException("No recoverable video stream");
    }

    private static JsonObject readJson(Path file) throws IOException {
        if (Files.size(file) > 1_048_576) throw new IOException("Recovery metadata is too large: " + file);
        JsonObject data = GSON.fromJson(Files.readString(file), JsonObject.class);
        if (data == null) throw new IOException("Empty recovery metadata: " + file);
        return data;
    }
}
