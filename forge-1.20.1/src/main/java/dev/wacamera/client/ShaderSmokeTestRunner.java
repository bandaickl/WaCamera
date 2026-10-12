package dev.wacamera.client;

import com.mojang.logging.LogUtils;
import dev.wacamera.core.ShotDefinition;
import dev.wacamera.core.ShotPoint;
import dev.wacamera.core.Vec3d;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Explicit shader regression in SmokeTestRunner's newly created world. Never opens or edits an existing save. */
public final class ShaderSmokeTestRunner {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    private static final double RECORD_SECONDS = 8;
    private static final long SECOND = 1_000_000_000L;
    private enum Phase { INITIAL, WAIT_SCENE, WARMUP, PREVIEW, RECORDING, VERIFY_SERVER, FINISHED }
    private static Phase phase = Phase.INITIAL;
    private static long started, warmUntil, previewStarted, recordingStarted;
    private static int scene, cameraChecks, reachableHits, placedBlocks;
    private static BlockPos origin;
    private static UUID playerId;
    private static Camera playerCamera;
    private static ShotDefinition shot;
    private static Path previousVideo;
    private static CompletableFuture<Void> sceneWork;
    private static CompletableFuture<Integer> serverCount;
    private static boolean referenceSaved, playerViewSaved, observedRecording;
    private static Vec3 movementStart;
    private static float yawStart;
    private static double greatestMovement, greatestRotation;
    private static Method shaderApiGetter, shaderApiInUse;

    private ShaderSmokeTestRunner() { }

    public static boolean enabled() { return Boolean.getBoolean("wacamera.smokeShaders"); }

    /** Called from the existing smoke tick after world warmup and command checks. */
    public static void tick() throws Exception {
        if (!enabled() || phase == Phase.FINISHED) return;
        Minecraft mc = Minecraft.getInstance();
        mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
        long now = System.nanoTime();
        if (started == 0) started = now;
        try {
            require(now - started < 120 * SECOND, "Shader smoke exceeded 120 seconds in " + phase);
            require(mc.level != null && mc.player != null && mc.player.isAlive(), "No living player in shader fixture");
            switch (phase) {
                case INITIAL -> initialize(mc);
                case WAIT_SCENE -> {
                    if (!sceneWork.isDone()) return;
                    sceneWork.get();
                    if (!mc.level.getBlockState(origin).is(Blocks.WHITE_CONCRETE)
                            || mc.player.position().distanceTo(startPosition()) > 1.5) return;
                    warmUntil = now + 5 * SECOND;
                    phase = Phase.WARMUP;
                    report("Scene " + sceneName() + " synchronized; warming ordinary shader view for 5 seconds");
                }
                case WARMUP -> {
                    releaseInputs(mc);
                    if (now < warmUntil) return;
                    requireActiveShaders();
                    shot = createShot(mc, 6, 0);
                    previousVideo = WaCameraClient.lastVideo();
                    referenceSaved = false;
                    previewStarted = now;
                    WaCameraClient.start(shot, true);
                    phase = Phase.PREVIEW;
                    report("Started single-view " + sceneName() + " preview reference at " + shot.points().get(0));
                }
                case PREVIEW -> preview(mc, now);
                case RECORDING -> recording(mc, now);
                case VERIFY_SERVER -> finishScene(mc);
                case FINISHED -> { }
            }
        } catch (Exception failure) {
            releaseInputs(mc);
            report("FAILED in " + phase + ": " + failure);
            throw failure;
        }
    }

    private static void initialize(Minecraft mc) throws Exception {
        MinecraftServer server = mc.getSingleplayerServer();
        require(server != null && server.getWorldData().getLevelName().startsWith("WaCamera-smoke-"),
                "Shader fixture is allowed only in the newly generated WaCamera smoke world");
        Files.createDirectories(WaCameraClient.root());
        Files.writeString(reportPath(), "Shader two-view test; audio output muted\n"
                + "Display=" + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight()
                + "; recording=1280x720@30; duration=8 seconds per scene\n"
                + "Single-view references retain display dimensions and the same vertical FOV; compare aspect-correct crops.\n");
        requireActiveShaders();
        require(!WaCameraClient.busy(), "Another camera operation is active");
        report("Iris shader API reports active=true");
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        mc.options.hideGui = false;
        mc.options.pauseOnLostFocus = false;
        mc.setScreen(null);
        playerCamera = mc.gameRenderer.getMainCamera();
        playerId = mc.player.getUUID();
        origin = mc.player.blockPosition().below();
        var settings = WaCameraClient.settings();
        settings.ffmpeg = System.getProperty("wacamera.smokeFfmpeg", settings.ffmpeg);
        settings.audio = Boolean.getBoolean("wacamera.smokeAudio");
        settings.quality = "720p30";
        var dimension = mc.level.dimension();
        sceneWork = server.submit(() -> {
            ServerLevel world = server.getLevel(dimension);
            if (world == null) throw new IllegalStateException("Fixture world disappeared");
            buildFixture(world);
            prepareScene(server, world, false);
        });
        phase = Phase.WAIT_SCENE;
        report("Building bounded white-floor, arch, emissive-lamp fixture at " + origin);
    }

    private static void buildFixture(ServerLevel world) {
        // The parent created a flat world. A 25x25 pad stays close to both cameras and shadow-map coverage.
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-12, 0, -12), origin.offset(12, 0, 12)))
            world.setBlock(pos, Blocks.WHITE_CONCRETE.defaultBlockState(), 3);
        for (int x : new int[]{-3, 3}) {
            for (int y = 1; y <= 5; y++)
                world.setBlock(origin.offset(x, y, 1), Blocks.QUARTZ_PILLAR.defaultBlockState(), 3);
        }
        for (int x = -3; x <= 3; x++)
            world.setBlock(origin.offset(x, 5, 1), Blocks.QUARTZ_BLOCK.defaultBlockState(), 3);
        // Fixed, high-contrast silhouettes and a dark background expose shadows, bloom and incorrect history.
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-7, 1, 7), origin.offset(7, 4, 7)))
            world.setBlock(pos, Blocks.BLACK_CONCRETE.defaultBlockState(), 3);
        world.setBlock(origin.offset(-4, 1, 5), Blocks.SEA_LANTERN.defaultBlockState(), 3);
        world.setBlock(origin.offset(4, 1, 5), Blocks.GLOWSTONE.defaultBlockState(), 3);
        for (int y = 1; y <= 3; y++) {
            world.setBlock(origin.offset(-8, y, 3), Blocks.RED_CONCRETE.defaultBlockState(), 3);
            world.setBlock(origin.offset(8, y, 3), Blocks.CYAN_CONCRETE.defaultBlockState(), 3);
        }
    }

    private static void prepareScene(MinecraftServer server, ServerLevel world, boolean night) {
        world.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        world.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        world.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        world.setWeatherParameters(6000, 0, false, false);
        world.setDayTime(night ? 18000 : 3000);
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-12, 1, -12), origin.offset(12, 8, 12)))
            if (world.getBlockState(pos).is(Blocks.OAK_PLANKS)) world.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) throw new IllegalStateException("Fixture player disappeared");
        Vec3 start = startPosition();
        player.getAbilities().flying = false;
        player.connection.teleport(start.x, start.y, start.z, 0, 40);
    }

    private static Vec3 startPosition() { return new Vec3(origin.getX() + .5, origin.getY() + 1, origin.getZ() - 4.5); }

    private static ShotDefinition createShot(Minecraft mc, double seconds, int countdown) {
        ShotPoint camera = new ShotPoint("shader-fixed", origin.getX() + 10.5, origin.getY() + 8,
                origin.getZ() - 10.5, 0, 0);
        return new ShotDefinition(1, "shader-" + sceneName(), mc.level.dimension().location().toString(),
                seconds, countdown, "linear", "constant", "target", 55, List.of(camera),
                new Vec3d(origin.getX() + .5, origin.getY() + 2, origin.getZ() + 1.5));
    }

    private static void preview(Minecraft mc, long now) throws Exception {
        releaseInputs(mc);
        if (WaCameraClient.state() == WaCameraClient.State.PREVIEW && !referenceSaved && now - previewStarted >= 4500_000_000L) {
            require(mc.gameRenderer.getMainCamera().isDetached(), "Reference preview did not switch to the registered camera");
            saveScreenshot(mc, "reference");
            referenceSaved = true;
        }
        if (WaCameraClient.state() != WaCameraClient.State.IDLE) return;
        require(referenceSaved, "Preview ended before its warmed reference image was captured");
        require(java.util.Objects.equals(previousVideo, WaCameraClient.lastVideo()), "Preview unexpectedly created a recording");
        requireActiveShaders();
        mc.player.setYRot(0);
        mc.player.setXRot(40);
        mc.player.getAbilities().flying = false;
        mc.player.onUpdateAbilities();
        mc.player.getInventory().selected = 0;
        ItemStack buildingBlocks = new ItemStack(Items.OAK_PLANKS, 64);
        mc.player.getInventory().setItem(0, buildingBlocks);
        mc.gameMode.handleCreativeModeItemAdd(buildingBlocks.copy(), 36);
        shot = createShot(mc, RECORD_SECONDS, 2);
        cameraChecks = reachableHits = placedBlocks = 0;
        greatestMovement = greatestRotation = 0;
        movementStart = mc.player.position();
        yawStart = mc.player.getYRot();
        recordingStarted = 0;
        playerViewSaved = observedRecording = false;
        previousVideo = WaCameraClient.lastVideo();
        WaCameraClient.start(shot, false);
        phase = Phase.RECORDING;
        report("Starting shader-enabled " + sceneName() + " recording; first 2 seconds keep the player still");
    }

    private static void recording(Minecraft mc, long now) throws Exception {
        var state = WaCameraClient.state();
        if (state == WaCameraClient.State.RECORDING) {
            if (!observedRecording) { observedRecording = true; recordingStarted = now; }
            double elapsed = (now - recordingStarted) / 1e9;
            mc.options.keyUp.setDown(elapsed >= 2.2 && elapsed < 4.0);
            mc.options.keyRight.setDown(elapsed >= 4.2 && elapsed < 5.0);
            mc.options.keyJump.setDown(elapsed >= 2.5 && elapsed < 2.7);
            mc.options.keyUse.setDown(elapsed >= 2.1 && elapsed < 6.1);
            mc.options.keyAttack.setDown(false);
            if (elapsed >= 2 && elapsed < 6) mc.player.turn(2, 0);
            greatestMovement = Math.max(greatestMovement, mc.player.position().distanceTo(movementStart));
            greatestRotation = Math.max(greatestRotation, Math.abs(net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot() - yawStart)));
            if (elapsed > .4) verifyPlayerView(mc);
            if (!playerViewSaved && elapsed >= 3.5) {
                saveScreenshot(mc, "player");
                playerViewSaved = true;
            }
            return;
        }
        releaseInputs(mc);
        if (state != WaCameraClient.State.IDLE) return;
        require(observedRecording && playerViewSaved, "Recording did not run: " + WaCameraClient.status());
        Path video = WaCameraClient.lastVideo();
        require(video != null && !video.equals(previousVideo) && Files.isRegularFile(video) && Files.size(video) > 1024,
                "No finalized shader recording: " + WaCameraClient.status());
        require(cameraChecks >= 20 && reachableHits >= 10, "Insufficient first-person camera or reach checks");
        require(greatestMovement > .5 && greatestRotation > 5, "Player did not independently move and rotate");
        requireActiveShaders();
        verifyPlayerView(mc);
        report("Saved " + sceneName() + " video=" + video.toAbsolutePath()
                + "; cameraChecks=" + cameraChecks + "; reachableHits=" + reachableHits
                + "; movement=" + greatestMovement + "; yawChange=" + greatestRotation);
        var server = mc.getSingleplayerServer();
        var dimension = mc.level.dimension();
        serverCount = server.submit(() -> {
            ServerLevel world = server.getLevel(dimension);
            if (world == null) throw new IllegalStateException("Fixture world disappeared before placement verification");
            int count = 0;
            for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-12, 1, -12), origin.offset(12, 8, 12)))
                if (world.getBlockState(pos).is(Blocks.OAK_PLANKS)) count++;
            return count;
        });
        phase = Phase.VERIFY_SERVER;
    }

    private static void finishScene(Minecraft mc) throws Exception {
        if (!serverCount.isDone()) return;
        placedBlocks = serverCount.get();
        require(placedBlocks > 0, "No player-placed blocks reached the authoritative server");
        report("Verified " + sceneName() + " server blocks=" + placedBlocks);
        if (scene == 0) {
            scene = 1;
            var server = mc.getSingleplayerServer();
            var dimension = mc.level.dimension();
            sceneWork = server.submit(() -> {
                ServerLevel world = server.getLevel(dimension);
                if (world == null) throw new IllegalStateException("Fixture world disappeared before night scene");
                prepareScene(server, world, true);
            });
            phase = Phase.WAIT_SCENE;
        } else {
            report("PASS: active shader daytime/nighttime previews and separate MP4 recordings; first-person camera, movement, rotation, reach and server-confirmed building preserved. Inspect reference/MP4 shadow and bloom images for shader fidelity.");
            phase = Phase.FINISHED;
            mc.stop();
        }
    }

    private static void verifyPlayerView(Minecraft mc) {
        Camera camera = mc.gameRenderer.getMainCamera();
        require(camera == playerCamera && mc.getCameraEntity() == mc.player && camera.getEntity() == mc.player
                        && mc.options.getCameraType() == CameraType.FIRST_PERSON && !camera.isDetached()
                        && camera.getPosition().distanceTo(mc.player.getEyePosition()) < 1.2,
                "Shader capture replaced the ordinary first-person camera");
        if (mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS) {
            require(mc.hitResult.getLocation().distanceTo(mc.player.getEyePosition()) <= 7,
                    "Shader capture moved the interaction ray outside the player's reach");
            reachableHits++;
        }
        cameraChecks++;
    }

    private static void requireActiveShaders() throws Exception {
        if (shaderApiGetter == null) {
            for (String name : new String[]{"net.irisshaders.iris.api.v0.IrisApi", "net.coderbot.iris.api.v0.IrisApi"}) {
                try {
                    Class<?> api = Class.forName(name);
                    shaderApiGetter = api.getMethod("getInstance");
                    shaderApiInUse = api.getMethod("isShaderPackInUse");
                    break;
                } catch (ClassNotFoundException ignored) { }
            }
        }
        require(shaderApiGetter != null, "Shader smoke requires Oculus/Iris and an active shader pack");
        require(Boolean.TRUE.equals(shaderApiInUse.invoke(shaderApiGetter.invoke(null))), "The shader pack is not active");
    }

    private static void saveScreenshot(Minecraft mc, String kind) throws Exception {
        Path file = WaCameraClient.root().resolve("smoke-shader-" + sceneName() + "-" + kind + ".png");
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(file); }
        report("Screenshot " + file.toAbsolutePath());
    }

    private static void releaseInputs(Minecraft mc) {
        mc.options.keyUp.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyUse.setDown(false);
        mc.options.keyAttack.setDown(false);
    }

    private static String sceneName() { return scene == 0 ? "day" : "night"; }
    private static Path reportPath() { return WaCameraClient.root().resolve("smoke-shaders.txt"); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
    private static void report(String message) {
        LOG.info("[WaCamera shader smoke] {}", message);
        try {
            Files.createDirectories(WaCameraClient.root());
            Files.writeString(reportPath(), message + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException failure) { LOG.warn("Could not save shader smoke report", failure); }
    }
}
