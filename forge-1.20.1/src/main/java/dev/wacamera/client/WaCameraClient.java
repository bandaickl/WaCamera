package dev.wacamera.client;

import dev.wacamera.core.*;
import dev.wacamera.recorder.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** All world and GL access stays on the Minecraft thread. Encoding and preparation do not. */
public final class WaCameraClient {
    public enum State { IDLE, PREPARING, COUNTDOWN, PREVIEW, RECORDING, FINALIZING }
    private static State state = State.IDLE;
    private static ClientSettings settings;
    private static ShotDefinition edited;
    // Merely opening the default draft must not make the first /wac cam append point 2.
    private static boolean explicitDraft;
    private static ShotDefinition shot;
    private static PathSampler sampler;
    private static PathSampler markerSampler;
    private static FfmpegRecorder recorder;
    private static GlFrameCapture capture;
    private static DualPassRenderer dualPass;
    private static boolean recordingPass;
    private static String deferredStop;
    private static ClientLevel level;
    private static UUID playerId;
    private static long epoch;
    private static long countdownEnd;
    private static long generation;
    private static int outputWidth, outputHeight, outputFps;
    private static boolean originalSmartCull;
    private static boolean changedCull;
    private static String status = "";
    private static long noticeUntil;
    private static long frameNanos;
    private static CameraPose pose;
    private static boolean frameRendered;
    private static boolean pendingFirstFrame;
    private static CompletableFuture<Path> finalization = CompletableFuture.completedFuture(null);
    private static Path lastVideo;
    private static CompletableFuture<FfmpegRecorder> preparation;
    private static volatile boolean shuttingDown;
    private static boolean recoveryScanStarted;

    private WaCameraClient() { }
    public static Path root() { return Minecraft.getInstance().gameDirectory.toPath().resolve("wacamera"); }
    public static ClientSettings settings() { if (settings == null) settings = ClientSettings.load(root()); return settings; }
    public static ShotRepository repository() { return new ShotRepository(root().resolve("shots")); }
    public static ShotPatternRepository patterns() { return new ShotPatternRepository(root().resolve("patterns")); }
    public static State state() { return state; }
    /** Suppress only focus-triggered pause while preparing or capturing a movie. */
    public static boolean isBackgroundRecording() {
        return state == State.PREPARING || state == State.COUNTDOWN || state == State.RECORDING;
    }
    /** Keep enough display frames to service the offscreen camera and asynchronous pixel transfers. */
    public static int backgroundFrameRateLimit(int vanillaLimit) {
        if (!isBackgroundRecording() || Minecraft.getInstance().isWindowActive() || vanillaLimit <= 0)
            return vanillaLimit;
        double speed = shot == null ? 1 : shot.playbackSpeed();
        int needed = Math.max(10, (int) Math.ceil(outputFps / speed));
        return Math.max(vanillaLimit, needed);
    }
    public static boolean cameraActive() { return state == State.PREVIEW || recordingPass; }
    public static boolean isRecordingPass() { return recordingPass; }
    public static void setRecordingPass(boolean value) { recordingPass = value; }
    public static boolean recordingFrameReady() { return frameRendered && deferredStop == null; }
    public static net.minecraft.client.Camera recordingCamera() { return dualPass == null ? null : dualPass.camera(); }
    public static com.mojang.blaze3d.pipeline.RenderTarget recordingTarget() { return dualPass == null ? null : dualPass.target(); }
    public static long recordedRenderFrames() { return dualPass == null ? 0 : dualPass.renderedFrames(); }
    public static boolean busy() { return state != State.IDLE; }
    public static String status() { return status; }
    public static CameraPose currentPose() { return pose; }
    /** Player-only guide: sampling it must never advance capture timestamps or mark a frame ready. */
    public static CameraPose markerPose(float partialTick) {
        if (recordingPass || (state != State.COUNTDOWN && state != State.RECORDING)
                || markerSampler == null || shot == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level || mc.player == null || mc.options.hideGui) return null;
        double seconds = state == State.RECORDING && recorder != null && recorder.startNanos() != 0
                ? Math.max(0, (System.nanoTime() - recorder.startNanos()) / 1e9) : 0;
        return markerSampler.sample(Math.min(seconds, shot.durationSeconds()), selfTarget(partialTick));
    }
    public static double recordingAspectRatio() {
        return outputHeight > 0 ? (double) outputWidth / outputHeight : 16.0 / 9.0;
    }
    public static Path lastVideo() { return lastVideo; }
    public static void checkRecoveryNotice() {
        Minecraft mc = Minecraft.getInstance();
        if (recoveryScanStarted || mc.player == null) return;
        recoveryScanStarted = true;
        CompletableFuture.supplyAsync(() -> {
            try { return RecordingRecovery.listRecoverySessions(root().resolve("recordings")).stream().filter(s -> !s.active()).count(); }
            catch (Exception e) { return 0L; }
        }).thenAccept(count -> mc.execute(() -> { if (count > 0) notice(Component.translatable("wacamera.recovery_found", count).getString()); }));
    }

    public static CompletableFuture<Path> recover(Path session, boolean silent) {
        if (busy()) throw new IllegalStateException(Component.translatable("wacamera.error.busy").getString());
        Path ffmpeg = Path.of(settings().ffmpeg);
        state = State.FINALIZING;
        finalization = silent ? RecordingRecovery.recoverSilentFromSession(session, ffmpeg) : RecordingRecovery.recoverFromSession(session, ffmpeg);
        finalization.whenComplete((path, failure) -> Minecraft.getInstance().execute(() -> {
            state = State.IDLE;
            if (failure != null) error(failure);
            else { lastVideo = path; notice(Component.translatable("wacamera.saved", path.getFileName().toString()).getString()); }
        }));
        return finalization;
    }

    public static ShotDefinition edited() {
        if (edited == null) {
            Minecraft mc = Minecraft.getInstance();
            String dimension = mc.level == null ? "minecraft:overworld" : mc.level.dimension().location().toString();
            edited = new ShotDefinition(1, "My shot", dimension, 10, 3, "linear", "constant", "registered", 70, List.of(pointHere()), null);
        }
        return edited;
    }
    public static void setEdited(ShotDefinition value) { edited = value; explicitDraft = value != null; }

    /** Ordered command edits share exactly the draft displayed by the K panel. No file is overwritten. */
    public static int registerCommandPoint(Integer oneBasedIndex, ShotPoint point) {
        ShotDefinition current = commandDraft();
        int registered = explicitDraft ? current.points().size() : 0;
        int index = oneBasedIndex == null ? registered + 1 : oneBasedIndex;
        if (index > 64) throw commandError("wacamera.command.error.full");
        if (index < 1) throw commandError("wacamera.command.error.missing", index, registered);
        if (index > registered + 1) throw commandError("wacamera.command.error.gap", index, registered + 1);
        setEdited(ShotEditor.setPoint(current, index, point.x(), point.y(), point.z(), point.yawDegrees(), point.pitchDegrees()));
        return index;
    }

    public static ShotPoint commandPoint(int oneBasedIndex) {
        ShotDefinition current = commandDraft();
        int registered = explicitDraft ? current.points().size() : 0;
        if (oneBasedIndex < 1 || oneBasedIndex > registered)
            throw commandError("wacamera.command.error.missing", oneBasedIndex, registered);
        return ShotEditor.pointAt(current, oneBasedIndex);
    }

    private static ShotDefinition commandDraft() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !mc.player.isAlive()) throw commandError("wacamera.command.error.no_player");
        if (busy()) throw commandError("wacamera.error.busy");
        ShotDefinition current = edited();
        if (!current.dimension().equals(mc.level.dimension().location().toString())) throw commandError("wacamera.error.dimension");
        return current;
    }

    private static IllegalStateException commandError(String key, Object... args) {
        return new IllegalStateException(Component.translatable(key, args).getString());
    }
    public static ShotPoint pointHere() {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        var p = camera.getPosition();
        return new ShotPoint(UUID.randomUUID().toString(), p.x, p.y, p.z, camera.getYRot(), camera.getXRot());
    }

    public static void openPanel() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (busy()) stop("wacamera.stopped");
        mc.setScreen(new ShotScreen());
    }

    public static void start(ShotDefinition value, boolean preview) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (busy()) throw new IllegalStateException(Component.translatable("wacamera.error.busy").getString());
        if (mc.level == null || mc.player == null || !mc.player.isAlive()) throw new IllegalStateException("No living player");
        if (!value.dimension().equals(mc.level.dimension().location().toString())) throw new IllegalArgumentException(Component.translatable("wacamera.error.dimension").getString());
        if (!preview) {
            ShaderCompatibility.validateRecording();
            ShaderCapturePipeline.validate();
            EmbeddiumCompatibility.validate();
        }
        PathSampler candidate = new PathSampler(value);
        validateLoaded(mc, value, candidate);
        CameraPose initialPose = candidate.sample(0, selfTarget(1));
        // Resolve every synchronous validation failure before retaining world/player references.
        RecordingOptions options = preview ? null : recordingOptions(value);
        settings().save(root());
        shot = value;
        sampler = candidate;
        // Look-at sampling remembers the last stable angles at poles; the guide needs its own history.
        markerSampler = new PathSampler(value);
        level = mc.level;
        playerId = mc.player.getUUID();
        setEdited(value);
        mc.setScreen(null);
        pose = initialPose;
        if (preview) {
            state = State.PREVIEW;
            epoch = System.nanoTime();
            enableCamera();
            return;
        }
        state = State.PREPARING;
        final long request = ++generation;
        try {
            preparation = CompletableFuture.supplyAsync(() -> {
                FfmpegRecorder prepared = new FfmpegRecorder(options);
                try { prepared.start(); return prepared; }
                catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
            });
        } catch (RuntimeException e) {
            releaseCamera();
            state = State.IDLE;
            ++generation;
            throw e;
        }
        preparation.whenComplete((prepared, failure) -> {
            if (shuttingDown) { if (prepared != null) prepared.finish(); return; }
            mc.execute(() -> {
            if (request != generation || state != State.PREPARING) {
                if (prepared != null) prepared.finish();
                return;
            }
            if (failure != null) {
                releaseCamera();
                state = State.IDLE;
                error(failure);
                return;
            }
            recorder = prepared;
            try {
                capture = new GlFrameCapture(outputWidth, outputHeight);
                dualPass = new DualPassRenderer(outputWidth, outputHeight, outputFps, shot.playbackSpeed());
            }
            catch (RuntimeException e) { error(e); stop("wacamera.error.capture"); return; }
            state = State.COUNTDOWN;
            countdownEnd = System.nanoTime() + shot.countdownSeconds() * 1_000_000_000L;
            epoch = 0;
            pendingFirstFrame = true;
            });
        });
    }

    private static RecordingOptions recordingOptions(ShotDefinition value) {
        String preset = settings().quality;
        outputWidth = preset.startsWith("1080") ? 1920 : 1280;
        outputHeight = preset.startsWith("1080") ? 1080 : 720;
        outputFps = preset.endsWith("60") ? 60 : preset.endsWith("24") ? 24 : 30;
        // The independent depth target renders at this resolution, regardless of the game window size.
        boolean recordAudio = settings().audio && value.playbackSpeed() == 1;
        Path helper = !recordAudio || settings().audioHelper.isBlank() ? null : Path.of(settings().audioHelper);
        return new RecordingOptions(Path.of(settings().ffmpeg), root().resolve("recordings"), outputWidth, outputHeight,
                outputFps, value.durationSeconds(), "libx264", helper, recordAudio, value.playbackSpeed());
    }

    private static void enableCamera() {
        Minecraft mc = Minecraft.getInstance();
        originalSmartCull = mc.smartCull;
        mc.smartCull = false;
        changedCull = true;
    }

    private static void releaseCamera() {
        Minecraft mc = Minecraft.getInstance();
        if (changedCull) mc.smartCull = originalSmartCull;
        changedCull = false;
        pose = null;
        level = null;
        playerId = null;
        sampler = null;
        markerSampler = null;
        frameRendered = false;
        pendingFirstFrame = false;
        deferredStop = null;
    }

    /** Called before each complete display frame, even when the HUD is hidden. */
    public static void beforeRender() {
        frameRendered = false;
        if (state == State.IDLE || state == State.FINALIZING) return;
        // Transfers must finish on display frames too: a timelapse camera may draw only once every few seconds.
        if (capture != null && recorder != null) {
            try { capture.pollReady(recorder); }
            catch (RuntimeException e) { error(e); stop("wacamera.error.capture"); return; }
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level || mc.player == null || !mc.player.isAlive() || !mc.player.getUUID().equals(playerId)
                || mc.isPaused() || (state == State.PREVIEW && !mc.isWindowActive())) {
            stop("wacamera.stopped");
            return;
        }
        if (recorder != null && recorder.failure() != null && !recorder.failure().isBlank()) {
            notice(recorder.failure());
            stop(null);
            return;
        }
        if (state != State.PREVIEW) {
            try { ShaderCompatibility.validateRecording(); }
            catch (IllegalStateException e) { error(e); stop(null); return; }
        }
        if (state == State.RECORDING && recorder != null && !recorder.isRecording()) { stop("wacamera.saving"); return; }
        long now = System.nanoTime();
        if (state == State.COUNTDOWN && now >= countdownEnd) {
            state = State.RECORDING;
            if (dualPass != null) dualPass.resetCadence();
        }
        if (state == State.PREVIEW && (now - epoch) / 1e9 >= shot.durationSeconds()) stop("wacamera.preview.done");
        if (state == State.RECORDING && recorder.startNanos() != 0 && recorder.elapsedSeconds() >= shot.durationSeconds()) stop("wacamera.saving");
    }

    /** Camera.setup invokes this once, so the timestamp describes the rendered pose. */
    public static CameraPose sampleCamera(float partialTick) {
        if (!cameraActive() || sampler == null) return null;
        Minecraft mc = Minecraft.getInstance();
        frameNanos = System.nanoTime();
        double t = state == State.PREVIEW ? (frameNanos - epoch) / 1e9 : state == State.RECORDING && recorder.startNanos() != 0 ? (frameNanos - recorder.startNanos()) / 1e9 : 0;
        CameraPose next = sampler.sample(Math.min(t, shot.durationSeconds()), selfTarget(partialTick));
        if (!mc.level.hasChunkAt(BlockPos.containing(next.x(), next.y(), next.z()))) {
            if (recordingPass) deferredStop = "wacamera.error.unloaded";
            else stop("wacamera.error.unloaded");
            return null;
        }
        pose = next;
        frameRendered = true;
        return next;
    }

    /** The capture draw happens before, and independently from, the player's normal world draw. */
    public static void renderRecordingPass(net.minecraft.client.renderer.GameRenderer renderer, float tick, long finishTime) {
        if ((state != State.RECORDING && state != State.COUNTDOWN) || dualPass == null) return;
        try { ShaderCompatibility.validateRecording(); dualPass.render(renderer, tick, finishTime); }
        catch (RuntimeException e) { error(e); deferredStop = ""; }
        if (deferredStop != null) {
            String reason = deferredStop;
            deferredStop = null;
            stop(reason.isEmpty() ? null : reason);
        }
    }

    /** Only the offscreen world target is read; HUD and the player's hand never enter this buffer. */
    public static void captureFrame() {
        if (!recordingPass || state != State.RECORDING || !frameRendered || capture == null || recorder == null) return;
        var target = dualPass.target();
        try {
            capture.capture(target.frameBufferId, target.width, target.height, frameNanos, recorder, pendingFirstFrame);
            if (pendingFirstFrame) dualPass.startCadence(frameNanos);
            pendingFirstFrame = false;
        } catch (RuntimeException e) { error(e); deferredStop = ""; }
    }

    public static void stop(String messageKey) {
        // A callback from a rendering mod must not delete a framebuffer while it is being drawn.
        if (recordingPass) { deferredStop = messageKey == null ? "" : messageKey; return; }
        if (state == State.IDLE || state == State.FINALIZING) return;
        ++generation;
        FfmpegRecorder ending = recorder;
        GlFrameCapture endingCapture = capture;
        DualPassRenderer endingPass = dualPass;
        recorder = null;
        capture = null;
        dualPass = null;
        state = ending == null ? State.IDLE : State.FINALIZING;
        try {
            if (endingCapture != null && ending != null) endingCapture.drain(ending);
        } catch (RuntimeException e) { error(e); }
        finally {
            try { if (endingCapture != null) endingCapture.close(); }
            catch (RuntimeException e) { error(e); }
            finally {
                try { if (endingPass != null) endingPass.close(); }
                catch (RuntimeException e) { error(e); }
                finally { releaseCamera(); }
            }
        }
        if (messageKey != null) notice(Component.translatable(messageKey).getString());
        if (ending == null) return;
        try { finalization = ending.finish(); }
        catch (RuntimeException e) { finalization = CompletableFuture.failedFuture(e); }
        finalization.whenComplete((path, failure) -> Minecraft.getInstance().execute(() -> {
            state = State.IDLE;
            if (failure != null) error(failure);
            else if (path != null) {
                lastVideo = path;
                notice(Component.translatable("wacamera.saved", path.getFileName().toString()).getString());
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) mc.player.displayClientMessage(Component.translatable("wacamera.saved", path.toString()), false);
            }
        }));
    }

    /** Minecraft is exiting; allow the external muxer to finish before daemon threads die. */
    public static void shutdown() {
        shuttingDown = true;
        stop("wacamera.saving");
        long deadline = System.nanoTime() + 20_000_000_000L;
        try {
            if (preparation != null) {
                FfmpegRecorder pending = preparation.get(Math.max(1, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
                pending.finish().get(Math.max(1, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
            }
        }
        catch (Exception e) { error(e); }
        try { finalization.get(Math.max(1, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS); }
        catch (Exception e) { error(e); }
    }

    public static void overlay(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        String line = switch (state) {
            case PREPARING -> Component.translatable("wacamera.preparing").getString();
            case COUNTDOWN -> Component.translatable("wacamera.countdown", Math.max(1, (countdownEnd - System.nanoTime() + 999_999_999L) / 1_000_000_000L)).getString();
            case RECORDING -> "REC " + String.format(Locale.ROOT, "%.1f / %.1f s", recorder == null ? 0 : recorder.elapsedSeconds(), shot.durationSeconds()) + "  [F8]";
            case PREVIEW -> Component.translatable("wacamera.preview").getString() + "  [F8]";
            case FINALIZING -> Component.translatable("wacamera.saving").getString();
            default -> System.nanoTime() < noticeUntil ? status : "";
        };
        if (line.isEmpty()) return;
        graphics.fill(5, 5, Math.min(graphics.guiWidth() - 5, 15 + mc.font.width(line)), 23, 0xB0000000);
        graphics.drawString(mc.font, line, 10, 10, state == State.RECORDING ? 0xFFFF6666 : 0xFFFFFFFF, false);
    }

    public static Vec3d selfTarget(float tick) {
        var p = Minecraft.getInstance().player;
        if (p == null) return new Vec3d(0, 0, 0);
        return new Vec3d(p.xo + (p.getX() - p.xo) * tick, p.yo + (p.getY() - p.yo) * tick + p.getBbHeight() * .72, p.zo + (p.getZ() - p.zo) * tick);
    }

    private static void validateLoaded(Minecraft mc, ShotDefinition value, PathSampler candidate) {
        // Sample the actual curve, rather than checking only control points.
        int count = 128;
        for (int i = 1; i < value.points().size(); i++) {
            ShotPoint a = value.points().get(i - 1), b = value.points().get(i);
            double d = Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2));
            count = Math.min(16384, count + (int)Math.ceil(d / 4));
        }
        for (int i = 0; i <= count; i++) {
            CameraPose p = candidate.sample(value.durationSeconds() * i / count, selfTarget(1));
            if (!mc.level.hasChunkAt(BlockPos.containing(p.x(), p.y(), p.z()))) throw new IllegalArgumentException(Component.translatable("wacamera.error.unloaded").getString());
        }
    }
    public static void notice(String text) { status = text; noticeUntil = System.nanoTime() + 10_000_000_000L; }
    public static void error(Throwable cause) {
        while (cause.getCause() != null) cause = cause.getCause();
        notice(cause.getMessage() == null ? cause.toString() : cause.getMessage());
    }
    public static void openOutputFolder() { Util.getPlatform().openFile(root().resolve("recordings").toFile()); }
}
