package dev.wacamera.client;

import dev.wacamera.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Explicit development test in SmokeTestRunner's new world. Never runs in ordinary gameplay. */
final class PatternTimelapseSmokeTestRunner {
    private static int phase, recordingIndex, cameraChecks;
    private static long started, due, recordingStarted, mostRenders;
    private static ShotDefinition original;
    private static Path previous;
    private static boolean settingsSaved, playerImage;
    private static String previousQuality, previousHelper;
    private static boolean previousAudio;
    private static final double[] DURATIONS = {20, 2, 6};
    private static final double[] SPEEDS = {10, 1, 120};

    static boolean enabled() { return Boolean.getBoolean("wacamera.smokeTimelapse"); }

    static void tick() throws Exception {
        var mc = Minecraft.getInstance();
        mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
        long now = System.nanoTime();
        if (started == 0) started = now;
        try {
            require(now - started < 150_000_000_000L, "Pattern/timelapse smoke timed out at phase " + phase);
            switch (phase) {
                case 0 -> {
                    if (Boolean.getBoolean("wacamera.smokeShaders")) {
                        require(ShaderCompatibility.isShaderActive(), "Shader timelapse requires an active shader pack");
                        report("Shader API confirms active pack for timelapse regression");
                    }
                    require(mc.getSingleplayerServer() != null
                            && mc.getSingleplayerServer().getWorldData().getLevelName().startsWith("WaCamera-smoke-"),
                            "Refusing to test in an existing world");
                    require(WaCameraClient.patterns().list().stream().noneMatch(ShotPatternRepository.Slot::occupied),
                            "Pattern smoke requires ten empty slots; existing patterns were preserved");
                    var s = WaCameraClient.settings();
                    previousQuality = s.quality; previousAudio = s.audio; previousHelper = s.audioHelper;
                    settingsSaved = true;
                    s.ffmpeg = System.getProperty("wacamera.smokeFfmpeg", s.ffmpeg);
                    s.quality = "720p24"; s.audio = true; s.audioHelper = "";
                    var p = mc.player;
                    original = new ShotDefinition(ShotDefinition.CURRENT_SCHEMA_VERSION, "smoke-pattern-A",
                            mc.level.dimension().location().toString(), 20, 1, "linear", "constant", "self", 70,
                            List.of(new ShotPoint("left", p.getX()+5, p.getY()+3, p.getZ()-5, 15, 20),
                                    new ShotPoint("right", p.getX()-5, p.getY()+4, p.getZ()-5, -15, 25)), null, 10);
                    for (int slot = 2; slot <= ShotPatternRepository.SLOT_COUNT; slot++) {
                        WaCameraClient.patterns().save(slot, shot("smoke-pattern-" + (char)('A'+slot-1), 20, 10));
                    }
                    WaCameraClient.setEdited(original);
                    mc.setScreen(new ShotScreen());
                    clickKey("wacamera.tab.5");
                    clickKey("wacamera.pattern_save");
                    require(WaCameraClient.patterns().load(1).equals(original), "GUI save lost shot fields");
                    require(new ShotPatternRepository(WaCameraClient.root().resolve("patterns")).list().stream()
                            .filter(ShotPatternRepository.Slot::occupied).count() == 10, "Ten patterns were not persisted");
                    due = now + 1_000_000_000L; phase = 1;
                    report("GUI saved pattern A; fresh repository restored ten slots and all camera/speed settings");
                }
                case 1 -> {
                    if (now < due) return;
                    screenshot("smoke-patterns.png");
                    edit("wacamera.field.name", "unsaved-edit");
                    clickKey("wacamera.pattern_load");
                    require(mc.screen instanceof ConfirmScreen, "Loading over edits needs confirmation");
                    phase = 2;
                }
                case 2 -> {
                    if (!pressIfActive(Component.translatable("gui.yes").getString())) return;
                    require(WaCameraClient.edited().equals(original), "GUI load did not restore saved camera and speed");
                    mc.screen.onClose();
                    WaCameraClient.setEdited(null);
                    WaCameraClient.setEdited(new ShotPatternRepository(WaCameraClient.root().resolve("patterns")).load(1));
                    mc.setScreen(new ShotScreen());
                    clickKey("wacamera.tab.3");
                    due = now + 1_000_000_000L; phase = 3;
                    report("GUI restored coordinates, yaw/pitch, path, FOV and 10x speed after draft reset");
                }
                case 3 -> {
                    if (now < due) return;
                    screenshot("smoke-timelapse-settings.png");
                    previous = WaCameraClient.lastVideo();
                    clickKey("wacamera.record");
                    require(WaCameraClient.busy(), "GUI did not start timelapse: " + WaCameraClient.status());
                    recordingStarted = 0; mostRenders = 0; cameraChecks = 0; playerImage = false; phase = 4;
                }
                case 4 -> {
                    mostRenders = Math.max(mostRenders, WaCameraClient.recordedRenderFrames());
                    if (WaCameraClient.state() == WaCameraClient.State.RECORDING) {
                        if (recordingStarted == 0) recordingStarted = now;
                        var camera = mc.gameRenderer.getMainCamera();
                        require(mc.options.getCameraType().isFirstPerson() && !camera.isDetached()
                                && mc.getCameraEntity() == mc.player
                                && camera.getPosition().distanceTo(mc.player.getEyePosition()) < 1.2,
                                "Timelapse replaced the normal first-person view");
                        cameraChecks++;
                        mc.options.keyUp.setDown(recordingIndex == 0 && cameraChecks % 80 < 30);
                        mc.options.keyJump.setDown(recordingIndex == 0 && cameraChecks % 60 < 3);
                        if (recordingIndex == 0) mc.player.turn(.3, 0);
                        if (!playerImage && now - recordingStarted > 1_000_000_000L) {
                            screenshot("smoke-timelapse-player-" + recordingIndex + ".png"); playerImage = true;
                        }
                    }
                    if (WaCameraClient.state() != WaCameraClient.State.IDLE) return;
                    releaseInputs(mc);
                    Path video = WaCameraClient.lastVideo();
                    require(recordingStarted != 0 && video != null && !video.equals(previous), "Missing video: " + WaCameraClient.status());
                    require(cameraChecks >= 15, "Too few normal-view samples");
                    long lower = recordingIndex == 0 ? 44 : recordingIndex == 1 ? 48 : 2;
                    long upper = recordingIndex == 0 ? 55 : recordingIndex == 1 ? 100 : 5;
                    require(mostRenders >= lower && mostRenders <= upper, "Capture cadence not reduced: " + mostRenders);
                    require(now - recordingStarted >= (DURATIONS[recordingIndex] - .5) * 1e9, "Recording finished before real capture duration");
                    report("PASS recording speed=" + SPEEDS[recordingIndex] + ", actualSeconds=" + DURATIONS[recordingIndex]
                            + ", fps=" + (recordingIndex == 1 ? 30 : 24) + ", cameraRenders=" + mostRenders
                            + ", normalCameraChecks=" + cameraChecks + ", video=" + video);
                    recordingIndex++;
                    if (recordingIndex < SPEEDS.length) {
                        var s = WaCameraClient.settings();
                        s.quality = recordingIndex == 1 ? "720p30" : "720p24";
                        s.audio = recordingIndex != 1; // A missing helper must not prevent either timelapse.
                        previous = video;
                        WaCameraClient.start(shot("smoke-speed-" + recordingIndex, DURATIONS[recordingIndex], SPEEDS[recordingIndex]), false);
                        recordingStarted = 0; mostRenders = 0; cameraChecks = 0; playerImage = false;
                    } else {
                        cleanup();
                        report("PASS: ten persistent patterns, GUI save/load, 10x and 120x silent capture, and 1x regression");
                        phase = 5; mc.stop();
                    }
                }
                default -> { }
            }
        } catch (Exception failure) {
            releaseInputs(mc);
            try { cleanup(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            report("FAILED: " + failure);
            throw failure;
        }
    }

    private static ShotDefinition shot(String name, double duration, double speed) {
        return new ShotDefinition(ShotDefinition.CURRENT_SCHEMA_VERSION, name, original.dimension(), duration, 1,
                original.positionInterpolation(), original.speedMode(), original.lookMode(), original.fovDegrees(),
                original.points(), original.target(), speed);
    }
    private static void clickKey(String key) {
        require(pressIfActive(Component.translatable(key).getString()), "No active GUI button " + key);
    }
    private static boolean pressIfActive(String caption) {
        var screen = Minecraft.getInstance().screen;
        if (screen == null) return false;
        for (var child : screen.children()) if (child instanceof Button button
                && button.getMessage().getString().equals(caption)) {
            if (!button.active) return false;
            button.onPress(); return true;
        }
        throw new IllegalStateException("Missing button: " + caption);
    }
    private static void edit(String key, String value) {
        for (var child : Minecraft.getInstance().screen.children()) if (child instanceof EditBox box
                && box.getMessage().getString().equals(Component.translatable(key).getString())) {
            box.setValue(value); return;
        }
        throw new IllegalStateException("Missing field: " + key);
    }
    private static void screenshot(String file) throws Exception {
        try (var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            image.writeToFile(WaCameraClient.root().resolve(file));
        }
    }
    private static void releaseInputs(Minecraft mc) {
        mc.options.keyUp.setDown(false); mc.options.keyJump.setDown(false);
    }
    private static void cleanup() throws Exception {
        if (!settingsSaved) return;
        var s = WaCameraClient.settings();
        s.quality = previousQuality; s.audio = previousAudio; s.audioHelper = previousHelper; s.save(WaCameraClient.root());
        for (int slot = 1; slot <= ShotPatternRepository.SLOT_COUNT; slot++) {
            var file = WaCameraClient.root().resolve("patterns").resolve(String.format(java.util.Locale.ROOT, "pattern-%02d.json", slot));
            if (Files.exists(file) && WaCameraClient.patterns().load(slot).name().startsWith("smoke-pattern-")) Files.delete(file);
        }
        settingsSaved = false;
    }
    private static void report(String message) throws Exception {
        Files.createDirectories(WaCameraClient.root());
        Files.writeString(WaCameraClient.root().resolve("smoke-timelapse.txt"), message + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.getLogger("wacamera").log(System.Logger.Level.INFO, message);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

