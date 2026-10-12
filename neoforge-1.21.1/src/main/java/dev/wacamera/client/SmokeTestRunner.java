package dev.wacamera.client;

import com.mojang.logging.LogUtils;
import dev.wacamera.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Explicit development opt-in only. Always creates a new world; never opens a user's save. */
public final class SmokeTestRunner {
    private static final boolean ENABLED = Boolean.getBoolean("wacamera.smokeTest");
    private static final int EXIT_SECONDS = Integer.getInteger("wacamera.smokeExitSeconds", 0);
    private static long boot;
    private static long entered;
    private static int phase;
    private static int ticks;
    private static boolean screenshot;
    private static Path previous;
    private static boolean uiCaptured;
    private static long uiOpened;
    private static BlockPos buildOrigin;
    private static int blocksBefore;
    private static int cameraChecks;
    private static long inventoryOpened;
    private static boolean inventoryVerified;
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    public static boolean enabled() { return ENABLED; }

    public static void tick() {
        if (!ENABLED && EXIT_SECONDS <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        // Smoke runs are always silent, including title-only startup checks.
        mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
        long now = System.nanoTime();
        if (boot == 0) boot = now;
        if (EXIT_SECONDS > 0 && now - boot >= EXIT_SECONDS * 1_000_000_000L) { mc.stop(); return; }
        if (!ENABLED) return;
        if (now - boot > 240_000_000_000L) { report("FAILED: smoke timeout"); mc.stop(); return; }
        try {
            if (phase == 0 && mc.screen != null && mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) mc.setScreen(new TitleScreen());
            if (phase == 0 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                phase = 1;
                mc.options.pauseOnLostFocus = false;
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                mc.options.hideGui = false;
                mc.options.graphicsMode().set(Boolean.getBoolean("wacamera.smokeFabulous") ? net.minecraft.client.GraphicsStatus.FABULOUS : net.minecraft.client.GraphicsStatus.FANCY);
                mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                String id = "WaCamera-smoke-" + UUID.randomUUID();
                report("Creating isolated world " + id);
                LevelSettings settings = new LevelSettings(id, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(id, settings, new WorldOptions(100L, false, false), access -> access.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
                return;
            }
            if (mc.player == null || mc.level == null) return;
            if (BackgroundRecordingSmokeTestRunner.enabled() && entered != 0
                    && now - entered >= 5_000_000_000L) {
                if (!CommandSmokeTestRunner.tick()) return;
                BackgroundRecordingSmokeTestRunner.tick();
                return;
            }
            if (PatternTimelapseSmokeTestRunner.enabled() && entered != 0
                    && now - entered >= 5_000_000_000L) {
                if (!CommandSmokeTestRunner.tick()) return;
                PatternTimelapseSmokeTestRunner.tick();
                return;
            }
            if (inventoryOpened != 0) {
                if (WaCameraClient.state() != WaCameraClient.State.RECORDING)
                    throw new IllegalStateException("Opening inventory stopped background recording");
                if (now - inventoryOpened >= 500_000_000L) {
                    try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(WaCameraClient.root().resolve("smoke-inventory-" + phase + ".png")); }
                    mc.setScreen(null);
                    inventoryOpened = 0;
                    inventoryVerified = true;
                    report("Inventory opened and closed while background recording continued");
                }
                return;
            }
            if (uiOpened != 0 && mc.screen instanceof ShotScreen) {
                if (now - uiOpened > 1_000_000_000L) {
                    try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(WaCameraClient.root().resolve("smoke-ui.png")); }
                    uiCaptured = true;
                    mc.setScreen(null);
                }
                return;
            }
            if (mc.screen != null) return;
            if (entered == 0) entered = now;
            if (now - entered < 5_000_000_000L) return;
            if (!CommandSmokeTestRunner.tick()) return;
            if (BackgroundRecordingSmokeTestRunner.enabled()) {
                BackgroundRecordingSmokeTestRunner.tick();
                return;
            }
            if (MarkerSmokeTestRunner.enabled()) {
                MarkerSmokeTestRunner.tick();
                return;
            }
            if (PatternTimelapseSmokeTestRunner.enabled()) {
                PatternTimelapseSmokeTestRunner.tick();
                return;
            }
            if (!uiCaptured) {
                uiOpened = now;
                Files.createDirectories(WaCameraClient.root());
                mc.setScreen(new ShotScreen());
                return;
            }
            if (phase == 1 || phase == 3 || phase == 5) {
                var p = mc.player;
                p.setYRot(0);
                p.setXRot(35);
                p.getInventory().selected = 0;
                ItemStack buildingBlocks = new ItemStack(Items.OAK_PLANKS, 64);
                p.getInventory().setItem(0, buildingBlocks);
                mc.gameMode.handleCreativeModeItemAdd(buildingBlocks.copy(), 36);
                buildOrigin = p.blockPosition();
                blocksBefore = countPlacedBlocks(mc);
                cameraChecks = 0;
                ticks = 0;
                inventoryVerified = false;
                var config = WaCameraClient.settings();
                config.ffmpeg = System.getProperty("wacamera.smokeFfmpeg", config.ffmpeg);
                config.audio = Boolean.getBoolean("wacamera.smokeAudio");
                config.quality = "720p30";
                ShotPoint a = new ShotPoint("a", p.getX() + 5, p.getY() + 3, p.getZ() - 5, 0, 0);
                ShotPoint b = new ShotPoint("b", p.getX() - 5, p.getY() + 4, p.getZ() - 5, 0, 0);
                ShotPoint c = new ShotPoint("c", p.getX() - 5, p.getY() + 3, p.getZ() + 5, 0, 0);
                if (phase == 5) a = new ShotPoint("same-facing", p.getX(), p.getY() + 4, p.getZ() - 5, p.getYRot(), p.getXRot());
                ShotDefinition shot = new ShotDefinition(1, "smoke-" + phase, mc.level.dimension().location().toString(), 4, 1, phase == 3 ? "smooth" : "linear", "constant", phase == 5 ? "registered" : "self", phase == 5 ? 45 : 70, phase == 3 ? List.of(a, b, c) : List.of(a), null);
                previous = WaCameraClient.lastVideo();
                screenshot = false;
                WaCameraClient.start(shot, false);
                CommandSmokeTestRunner.verifyBusy();
                phase++;
                report("Recording phase " + phase);
                return;
            }
            if (WaCameraClient.state() == WaCameraClient.State.RECORDING) {
                ticks++;
                mc.options.keyUp.setDown(ticks % 60 < 30);
                mc.options.keyJump.setDown(ticks % 40 < 3);
                mc.options.keyUse.setDown(ticks % 60 < 25);
                mc.options.keyAttack.setDown(ticks % 60 >= 40 && ticks % 60 < 50);
                pTurn(mc);
                if (ticks > 5) verifyPlayerView(mc);
                if (!screenshot && ticks % 30 == 0) {
                    screenshot = true;
                    Path file = WaCameraClient.root().resolve("smoke-view-" + phase + ".png");
                    try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(file); }
                }
                if (!inventoryVerified && ticks == 40) {
                    mc.options.keyUp.setDown(false);
                    mc.options.keyJump.setDown(false);
                    mc.options.keyUse.setDown(false);
                    mc.options.keyAttack.setDown(false);
                    inventoryOpened = now;
                    mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                }
            }
            if ((phase == 2 || phase == 4 || phase == 6) && WaCameraClient.state() == WaCameraClient.State.IDLE) {
                mc.options.keyUp.setDown(false);
                mc.options.keyJump.setDown(false);
                mc.options.keyUse.setDown(false);
                mc.options.keyAttack.setDown(false);
                Path video = WaCameraClient.lastVideo();
                if (video == null || video.equals(previous)) { report("FAILED: " + WaCameraClient.status()); mc.stop(); return; }
                int placed = countPlacedBlocks(mc) - blocksBefore;
                if (cameraChecks < 20 || placed <= 0 || !inventoryVerified) throw new IllegalStateException("Dual-view interaction test failed: checks=" + cameraChecks + ", placed=" + placed + ", inventory=" + inventoryVerified);
                report("Verified first-person display while recording: " + cameraChecks + " camera checks, " + placed + " placed blocks retained");
                report("Saved " + video);
                phase++;
            }
            if (phase == 7) {
                var p = mc.player;
                ShotPoint point = new ShotPoint("preview", p.getX() + 5, p.getY() + 3, p.getZ() - 5, 0, 0);
                ShotDefinition preview = new ShotDefinition(1, "smoke-preview", mc.level.dimension().location().toString(), 1.5, 0, "linear", "constant", "self", 70, List.of(point), null);
                previous = WaCameraClient.lastVideo();
                ticks = 0;
                screenshot = false;
                WaCameraClient.start(preview, true);
                phase = 8;
                return;
            }
            if (phase == 8) {
                ticks++;
                if (WaCameraClient.state() == WaCameraClient.State.PREVIEW && ticks == 10) {
                    if (!mc.gameRenderer.getMainCamera().isDetached()) throw new IllegalStateException("Preview did not show the recording camera");
                    try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(WaCameraClient.root().resolve("smoke-preview.png")); }
                    screenshot = true;
                }
                if (WaCameraClient.state() == WaCameraClient.State.IDLE) {
                    verifyPlayerView(mc);
                    if (!screenshot || !previous.equals(WaCameraClient.lastVideo())) throw new IllegalStateException("Preview did not finish cleanly");
                    report("PASS: first-person play/build, inventory, independent fixed/smooth/same-facing recording, and preview restoration completed");
                    mc.stop();
                }
            }
        } catch (Exception e) { LOG.error("WaCamera smoke test failed", e); report("FAILED: " + e); mc.stop(); }
    }
    private static void pTurn(Minecraft mc) { mc.player.turn(1.5, 0); }
    private static void verifyPlayerView(Minecraft mc) {
        var camera = mc.gameRenderer.getMainCamera();
        if (!mc.options.getCameraType().isFirstPerson() || camera.isDetached() || mc.getCameraEntity() != mc.player
                || camera.getPosition().distanceTo(mc.player.getEyePosition()) > 1.2) {
            throw new IllegalStateException("Recording replaced the player's first-person camera");
        }
        if (mc.hitResult != null && mc.hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS
                && mc.hitResult.getLocation().distanceTo(mc.player.getEyePosition()) > 7) {
            throw new IllegalStateException("Interaction ray escaped the player's reach");
        }
        cameraChecks++;
    }
    private static int countPlacedBlocks(Minecraft mc) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(buildOrigin.offset(-12, -4, -12), buildOrigin.offset(12, 8, 12)))
            if (mc.level.getBlockState(pos).is(Blocks.OAK_PLANKS)) count++;
        return count;
    }
    private static void report(String message) {
        LOG.info("[WaCamera smoke] {}", message);
        try {
            Files.createDirectories(WaCameraClient.root());
            Files.writeString(WaCameraClient.root().resolve("smoke-result.txt"), message + System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) { LOG.warn("Could not save smoke log", e); }
    }
}
