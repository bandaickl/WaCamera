package dev.wacamera.recorder;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Protocol client for the process-scoped Windows capture helper. */
final class AudioCapture implements AutoCloseable {
    record Metadata(int sampleRate, int channels, long totalFrames, long firstSampleNanos,
                    long lastSampleEndNanos, long nativeToJavaNanos, long clockUncertaintyNanos) {
        double spanSeconds() { return (lastSampleEndNanos - firstSampleNanos) / 1_000_000_000.0; }
        double tempoRatio() { return ((double) totalFrames / sampleRate) / spanSeconds(); }
        double offsetSeconds(long videoStart) {
            return ((firstSampleNanos - videoStart) + nativeToJavaNanos) / 1_000_000_000.0;
        }
    }

    private final Path helper;
    private final Path session;
    private final ArrayBlockingQueue<String> responses = new ArrayBlockingQueue<>(64);
    private Process process;
    private BufferedWriter commands;
    private long nativeToJavaNanos;
    private long uncertainty;
    private boolean stopped;

    AudioCapture(Path helper, Path session) { this.helper = helper; this.session = session; }

    void start() throws IOException {
        Path executable = ExecutableResolver.requireFile(helper);
        process = new ProcessBuilder(executable.toString(), "--pid", Long.toString(ProcessHandle.current().pid()),
                "--output", session.resolve("audio.wav").toString(), "--metadata", session.resolve("audio.json").toString())
                .redirectError(session.resolve("audio-stderr.log").toFile()).start();
        commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(this::readResponses, "WaCamera audio protocol");
        reader.setDaemon(true);
        reader.start();
        await("READY", 10_000);
        long bestRoundTrip = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long before = System.nanoTime();
            command("PING " + i);
            String reply = await("PONG " + i + " ", 3_000);
            long after = System.nanoTime();
            long nativeNanos;
            try { nativeNanos = Long.parseLong(reply.substring(reply.lastIndexOf(' ') + 1)); }
            catch (NumberFormatException e) { throw new IOException("Invalid helper clock response: " + reply, e); }
            long roundTrip = after - before;
            if (roundTrip < bestRoundTrip) {
                bestRoundTrip = roundTrip;
                nativeToJavaNanos = before + roundTrip / 2 - nativeNanos;
                uncertainty = roundTrip / 2;
            }
        }
        JsonObject clock = new JsonObject();
        clock.addProperty("nativeToJavaNanos", nativeToJavaNanos);
        clock.addProperty("uncertaintyNanos", uncertainty);
        Files.writeString(session.resolve("audio-clock.json"), new Gson().toJson(clock), StandardCharsets.UTF_8);
        if (uncertainty > 25_000_000L) throw new IOException("Audio clock handshake was too slow; retry recording.");
        command("START");
        await("STARTED", 5_000);
    }

    boolean isAlive() { return process != null && process.isAlive(); }

    Metadata stop() throws IOException {
        if (stopped) throw new IOException("Audio capture already stopped");
        stopped = true;
        command("STOP");
        await("STOPPED", 8_000);
        try {
            if (!process.waitFor(2_000, TimeUnit.MILLISECONDS)) throw new IOException("Audio helper did not exit after STOPPED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Audio helper stop interrupted", e);
        }
        if (process.exitValue() != 0) throw new IOException("Audio helper failed; see " + session.resolve("audio-stderr.log"));
        return readMetadata(session, nativeToJavaNanos, uncertainty);
    }

    static Metadata readMetadata(Path session) throws IOException {
        try {
            JsonObject clock = new Gson().fromJson(Files.readString(session.resolve("audio-clock.json")), JsonObject.class);
            return readMetadata(session, clock.get("nativeToJavaNanos").getAsLong(), clock.get("uncertaintyNanos").getAsLong());
        } catch (RuntimeException e) { throw new IOException("Could not read audio clock mapping", e); }
    }

    private static Metadata readMetadata(Path session, long nativeToJavaNanos, long uncertainty) throws IOException {
        try {
            JsonObject json = new Gson().fromJson(Files.readString(session.resolve("audio.json")), JsonObject.class);
            Metadata metadata = new Metadata(json.get("sampleRate").getAsInt(), json.get("channels").getAsInt(),
                    json.get("totalFrames").getAsLong(), json.get("firstSampleNanos").getAsLong(),
                    json.get("lastSampleEndNanos").getAsLong(), nativeToJavaNanos, uncertainty);
            if (metadata.sampleRate != 48_000 || metadata.channels != 2 || metadata.totalFrames <= 0
                    || metadata.lastSampleEndNanos <= metadata.firstSampleNanos || !Files.isRegularFile(session.resolve("audio.wav"))) {
                throw new IOException("Audio helper produced invalid audio metadata");
            }
            if (uncertainty < 0 || uncertainty > 25_000_000L) throw new IOException("Invalid or imprecise audio clock mapping");
            // A larger difference indicates missing/discontinuous samples, not ordinary device clock drift.
            if (!Double.isFinite(metadata.tempoRatio()) || Math.abs(metadata.tempoRatio() - 1.0) > 0.02) {
                throw new IOException("Audio capture clock/sample mismatch exceeds 2%; recovery data was retained");
            }
            return metadata;
        } catch (RuntimeException e) { throw new IOException("Could not read audio metadata", e); }
    }

    private void readResponses() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter log = Files.newBufferedWriter(session.resolve("audio-protocol.log"), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.write(line); log.newLine(); log.flush();
                if (!responses.offer(line)) {
                    responses.poll();
                    responses.offer(line);
                }
            }
        } catch (IOException e) { responses.offer("ERROR " + e.getMessage()); }
        finally { responses.offer("EOF"); }
    }

    private String await(String expected, long timeoutMillis) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("Audio helper timed out waiting for " + expected);
            try {
                String line = responses.poll(remaining, TimeUnit.NANOSECONDS);
                if (line == null) throw new IOException("Audio helper timed out waiting for " + expected);
                if (line.startsWith(expected)) return line;
                if (line.startsWith("ERROR") || line.equals("EOF")) throw new IOException("Audio helper: " + line);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Audio helper wait interrupted", e);
            }
        }
    }

    private synchronized void command(String command) throws IOException {
        if (commands == null) throw new IOException("Audio helper is not running");
        commands.write(command); commands.newLine(); commands.flush();
    }

    @Override public void close() {
        try { if (commands != null) commands.close(); } catch (IOException ignored) {}
        if (process != null && process.isAlive()) process.destroyForcibly();
    }
}
