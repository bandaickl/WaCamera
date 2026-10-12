package dev.wacamera.client;

import dev.wacamera.core.ShotDefinition;
import dev.wacamera.core.ShotPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWWindowFocusCallback;
import org.lwjgl.glfw.GLFWWindowFocusCallbackI;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Explicit, silent fixture for the new smoke world; never loads or modifies an existing save. */
final class BackgroundRecordingSmokeTestRunner {
    private static int phase, recordingIndex, inactiveChecks;
    private static long started, activeAt, inactiveFrames, inactiveWorldTime, restoredFrames, due;
    private static long iconifiedAt, iconifiedFrames, iconifiedWorldTime;
    private static boolean initialized, countdownObserved, focusRestored, iconifyCompleted, sawIconified;
    private static boolean iconifyInProgress, focusAttributeChanged, oldAudio, oldPauseOnLostFocus;
    private static int oldFocusOnShow;
    private static String oldQuality, oldFfmpeg;
    private static Boolean forcedActive;
    private static Path previousVideo;
    private static ShotDefinition shot;
    private static final String CHAT_TEXT = "WaCamera T テスト 123";
    private static ChatScreen testChat;
    private static boolean ownedChat, chatChecked;
    private static long chatAt, chatFrames, chatWorldTime;

    private BackgroundRecordingSmokeTestRunner() { }

    static boolean enabled() { return Boolean.getBoolean("wacamera.smokeBackground"); }

    static void tick() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
        long now = System.nanoTime();
        if (started == 0) started = now;
        try {
            require(now - started < 150_000_000_000L, "Background test timed out in phase " + phase);
            if (initialized) {
                require(mc.options.pauseOnLostFocus, "Recording changed the user's pauseOnLostFocus setting");
                // Client ticks precede GameRenderer.render, whose real focus-pause branch is under test.
                if (forcedActive != null) mc.setWindowActive(forcedActive);
            }
            switch (phase) {
                case 0 -> {
                    require(mc.getSingleplayerServer() != null && mc.level != null && mc.player != null
                                    && mc.getSingleplayerServer().getWorldData().getLevelName().startsWith("WaCamera-smoke-"),
                            "Not an isolated smoke world");
                    require(WaCameraClient.state() == WaCameraClient.State.IDLE, "Fixture must start idle");
                    require(!WaCameraClient.isBackgroundRecording(), "Idle is incorrectly treated as recording");
                    var settings = WaCameraClient.settings();
                    oldQuality = settings.quality;
                    oldFfmpeg = settings.ffmpeg;
                    oldAudio = settings.audio;
                    oldPauseOnLostFocus = mc.options.pauseOnLostFocus;
                    initialized = true;
                    mc.options.pauseOnLostFocus = true; // Undo SmokeTestRunner's general-purpose bypass.
                    settings.audio = false;
                    settings.ffmpeg = System.getProperty("wacamera.smokeFfmpeg", settings.ffmpeg);
                    mc.player.setYRot(0);
                    mc.player.setXRot(0);
                    start(false);
                    phase = 1;
                }
                case 1 -> {
                    observeInactive(now);
                    if (WaCameraClient.state() != WaCameraClient.State.RECORDING || activeAt == 0) return;
                    long elapsed = now - activeAt;
                    if (recordingIndex == 1 && !chatChecked && !exerciseChat(now)) return;
                    boolean exerciseIconify = recordingIndex == 0
                            && Boolean.getBoolean("wacamera.smokeBackgroundIconify");
                    if (exerciseIconify && !iconifyCompleted) {
                        if (!iconifyInProgress && elapsed >= 1_000_000_000L) minimize(now);
                        if (iconifyInProgress) {
                            sawIconified |= GLFW.glfwGetWindowAttrib(window(), GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE;
                            if (now - iconifiedAt < 2_000_000_000L) return;
                            long frames = WaCameraClient.recordedRenderFrames() - iconifiedFrames;
                            long ticks = mc.level.getGameTime() - iconifiedWorldTime;
                            restoreWindow();
                            require(sawIconified, "GLFW never reported the window as iconified");
                            require(frames >= 3, "No camera rendering while actually minimized: " + frames);
                            require(ticks >= 5, "World stopped while actually minimized: " + ticks);
                            iconifyCompleted = true;
                            report("PASS actual iconify: cameraFrames=" + frames + ", worldTicks=" + ticks);
                        }
                        if (!iconifyCompleted || elapsed < 3_300_000_000L) return;
                    } else if (elapsed < 2_200_000_000L) return;
                    long frames = WaCameraClient.recordedRenderFrames() - inactiveFrames;
                    long ticks = mc.level.getGameTime() - inactiveWorldTime;
                    require(frames >= (recordingIndex == 0 ? 10 : 2),
                            "Camera stopped rendering while unfocused: " + frames);
                    require(ticks >= 10, "World stopped while unfocused: " + ticks);
                    require(countdownObserved && inactiveChecks > 10, "Countdown/inactive branch was not exercised");
                    capture("smoke-background-inactive-" + recordingIndex + ".png");
                    report("PASS inactive speed=" + shot.playbackSpeed() + ", cameraFrames=" + frames
                            + ", worldTicks=" + ticks + ", checks=" + inactiveChecks);
                    setFocus(true);
                    focusRestored = true;
                    restoredFrames = WaCameraClient.recordedRenderFrames();
                    phase = 2;
                }
                case 2 -> {
                    require(mc.isWindowActive(), "Synthetic focus restoration failed");
                    require(!mc.isPaused() && (mc.screen == null || !mc.screen.isPauseScreen()),
                            "Focus restoration unexpectedly paused the game: state=" + WaCameraClient.state()
                                    + ", screen=" + mc.screen + ", paused=" + mc.isPaused()
                                    + ", status=" + WaCameraClient.status());
                    if (WaCameraClient.state() == WaCameraClient.State.RECORDING) {
                        if (WaCameraClient.recordedRenderFrames() > restoredFrames) focusRestored = false;
                        return;
                    }
                    if (WaCameraClient.state() != WaCameraClient.State.IDLE) return;
                    require(!focusRestored, "Camera did not render after focus restoration");
                    verifyMovie("natural completion");
                    require(!WaCameraClient.isBackgroundRecording(), "Natural completion still suppresses pause");
                    require(WaCameraClient.backgroundFrameRateLimit(7) == 7, "Idle changed the frame rate limit");
                    report("PASS complete speed=" + shot.playbackSpeed() + ", seconds=" + shot.durationSeconds()
                            + ", video=" + WaCameraClient.lastVideo());
                    start(true);
                    phase = 3;
                }
                case 3 -> {
                    observeInactive(now);
                    if (activeAt == 0 || now - activeAt < 1_000_000_000L) return;
                    setFocus(true);
                    // This is the same public operation invoked by Esc, outside GameRenderer's automatic-pause call.
                    mc.pauseGame(false);
                    require(mc.screen instanceof PauseScreen, "Manual pause was suppressed during recording");
                    due = now;
                    phase = 4;
                }
                case 4 -> {
                    require(mc.screen instanceof PauseScreen, "Manual pause screen was dismissed");
                    if (WaCameraClient.state() == WaCameraClient.State.RECORDING) {
                        require(now - due < 2_000_000_000L, "Manual pause failed to stop recording");
                        return;
                    }
                    require(!WaCameraClient.isBackgroundRecording(), "Manual pause still suppresses automatic pause");
                    require(now - due < 15_000_000_000L, "Paused movie did not finish saving");
                    if (WaCameraClient.state() != WaCameraClient.State.IDLE) return;
                    require(mc.isPaused(), "Manual PauseScreen did not pause the singleplayer world");
                    verifyMovie("manual pause");
                    report("PASS manual pause speed=" + shot.playbackSpeed() + ", video=" + WaCameraClient.lastVideo());
                    mc.setScreen(null);
                    due = now + 500_000_000L;
                    phase = 5;
                }
                case 5 -> {
                    if (now < due) return; // Let the client's cached paused flag clear before another start.
                    require(!mc.isPaused(), "Closing PauseScreen did not resume the world");
                    if (recordingIndex++ == 0) {
                        start(false);
                        phase = 1;
                    } else {
                        shot = createShot("background-preview", 6, 1);
                        WaCameraClient.start(shot, true);
                        require(!WaCameraClient.isBackgroundRecording(), "Preview incorrectly suppresses automatic pause");
                        setFocus(false);
                        due = now;
                        phase = 6;
                    }
                }
                case 6 -> {
                    require(WaCameraClient.backgroundFrameRateLimit(7) == 7, "Preview/idle changed background frame limit");
                    if (now - due < 1_200_000_000L) return;
                    require(WaCameraClient.state() == WaCameraClient.State.IDLE, "Preview did not stop on focus loss");
                    require(mc.screen instanceof PauseScreen && mc.isPaused(), "Vanilla focus-pause was not restored outside recording");
                    require(mc.options.pauseOnLostFocus, "Original pause option was changed");
                    report("PASS preview focus loss and idle automatic pause");
                    cleanup();
                    report("PASS: background recording, real-time and 10x, countdown, focus restoration, manual pause"
                            + (Boolean.getBoolean("wacamera.smokeBackgroundIconify") ? ", actual minimize/restore" : "")
                            + "; all audio muted");
                    phase = 7;
                    mc.stop();
                }
                default -> { }
            }
        } catch (Exception failure) {
            try { cleanup(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            try { report("FAILED: " + failure); } catch (Exception reporting) { failure.addSuppressed(reporting); }
            throw failure;
        }
    }

    private static void start(boolean manualPause) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        require(WaCameraClient.state() == WaCameraClient.State.IDLE, "Previous recording still active");
        double speed = recordingIndex == 0 ? 1 : 10;
        WaCameraClient.settings().quality = recordingIndex == 0 ? "720p30" : "720p24";
        shot = createShot("background-" + recordingIndex + (manualPause ? "-manual" : "-natural"),
                recordingIndex == 0 ? 6 : 8, speed);
        previousVideo = WaCameraClient.lastVideo();
        activeAt = 0;
        inactiveChecks = 0;
        countdownObserved = false;
        WaCameraClient.start(shot, false);
        require(WaCameraClient.state() == WaCameraClient.State.PREPARING
                && WaCameraClient.isBackgroundRecording(), "Preparation does not suppress automatic pause");
        setFocus(false);
        checkFrameLimit();
    }

    private static ShotDefinition createShot(String name, double duration, double speed) {
        Minecraft mc = Minecraft.getInstance();
        var p = mc.player;
        return new ShotDefinition(ShotDefinition.CURRENT_SCHEMA_VERSION, name,
                mc.level.dimension().location().toString(), duration, 1, "linear", "constant", "self", 70,
                List.of(new ShotPoint("a", p.getX() + 3, p.getEyeY() + 1, p.getZ() + 7, 0, 0),
                        new ShotPoint("b", p.getX() - 3, p.getEyeY() + 1, p.getZ() + 7, 0, 0)),
                null, speed);
    }

    private static void observeInactive(long now) {
        Minecraft mc = Minecraft.getInstance();
        require(!mc.isWindowActive(), "Focus loss was not delivered to Minecraft");
        require(WaCameraClient.isBackgroundRecording(), "Focus loss stopped recording: " + WaCameraClient.state());
        require(!mc.isPaused() && (mc.screen == null || !mc.screen.isPauseScreen()),
                "Automatic focus-pause opened during recording: state=" + WaCameraClient.state()
                        + ", screen=" + mc.screen + ", paused=" + mc.isPaused());
        require(mc.options.pauseOnLostFocus, "Focus-pause option was disabled instead of scoped suppression");
        checkFrameLimit();
        inactiveChecks++;
        if (WaCameraClient.state() == WaCameraClient.State.COUNTDOWN) countdownObserved = true;
        if (WaCameraClient.state() == WaCameraClient.State.RECORDING
                && WaCameraClient.recordedRenderFrames() > 0 && activeAt == 0) {
            activeAt = now;
            inactiveFrames = WaCameraClient.recordedRenderFrames();
            inactiveWorldTime = mc.level.getGameTime();
        }
    }

    /** Exercise actual chat widgets without pressing Enter or sending anything to the server. */
    private static boolean exerciseChat(long now) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        if (testChat == null) {
            if (mc.screen == null) {
                testChat = new ChatScreen("");
                ownedChat = true;
                mc.setScreen(testChat);
                for (char character : CHAT_TEXT.toCharArray()) testChat.charTyped(character, 0);
                require(CHAT_TEXT.equals(chatInput(testChat).getValue()), "Chat character input was not retained");
            } else if (mc.screen instanceof ChatScreen existing) {
                // Preserve an existing human draft: do not replace it or inject test characters into it.
                testChat = existing;
                ownedChat = false;
            } else {
                return false;
            }
            require(!testChat.isPauseScreen(), "Chat screen incorrectly pauses the world");
            chatAt = now;
            chatFrames = WaCameraClient.recordedRenderFrames();
            chatWorldTime = mc.level.getGameTime();
            return false;
        }
        if (mc.screen != testChat) {
            testChat = null; // The human closed it; retry without overwriting the new screen.
            return false;
        }
        if (now - chatAt < 750_000_000L) return false;
        require(WaCameraClient.state() == WaCameraClient.State.RECORDING && !mc.isPaused(),
                "Opening chat stopped background recording");
        long frames = WaCameraClient.recordedRenderFrames() - chatFrames;
        long ticks = mc.level.getGameTime() - chatWorldTime;
        require(frames >= 1, "No camera frame was captured while chat was open");
        require(ticks >= 3, "World time did not advance while chat was open");
        capture("smoke-background-chat.png");
        report("PASS chat during 10x recording: cameraFrames=" + frames + ", worldTicks=" + ticks
                + ", typedByFixture=" + ownedChat + "; no message submitted");
        if (ownedChat && CHAT_TEXT.equals(chatInput(testChat).getValue())) mc.setScreen(null);
        testChat = null;
        chatChecked = true;
        return true;
    }

    private static EditBox chatInput(ChatScreen screen) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                .findFirst().orElseThrow(() -> new IllegalStateException("Chat input widget missing"));
    }

    private static void checkFrameLimit() {
        int outputFps = recordingIndex == 0 ? 30 : 24;
        int expectedMinimum = (int) Math.ceil(outputFps / shot.playbackSpeed());
        require(WaCameraClient.backgroundFrameRateLimit(1) >= expectedMinimum,
                "Background frame rate is below source capture cadence");
        require(WaCameraClient.backgroundFrameRateLimit(120) == 120, "Background mode lowered a sufficient frame rate");
    }

    private static void setFocus(boolean active) {
        long handle = window();
        // GLFW has no getter for this callback. Retrieve and immediately restore the original;
        // it remains owned by Window and must never be freed by this fixture.
        GLFWWindowFocusCallback callback = GLFW.glfwSetWindowFocusCallback(handle, (GLFWWindowFocusCallbackI) null);
        GLFW.glfwSetWindowFocusCallback(handle, callback);
        require(callback != null, "Minecraft has no GLFW focus callback");
        callback.invoke(handle, active);
        require(Minecraft.getInstance().isWindowActive() == active, "GLFW focus callback did not update Minecraft");
        forcedActive = active;
    }

    private static void minimize(long now) throws Exception {
        oldFocusOnShow = GLFW.glfwGetWindowAttrib(window(), GLFW.GLFW_FOCUS_ON_SHOW);
        GLFW.glfwSetWindowAttrib(window(), GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        focusAttributeChanged = true;
        iconifyInProgress = true;
        sawIconified = false;
        iconifiedAt = now;
        iconifiedFrames = WaCameraClient.recordedRenderFrames();
        iconifiedWorldTime = Minecraft.getInstance().level.getGameTime();
        GLFW.glfwIconifyWindow(window());
        report("Actual window minimization requested for two seconds");
    }

    private static void restoreWindow() {
        try {
            if (iconifyInProgress) {
                // No glfwFocusWindow call: leave the user's foreground application alone.
                GLFW.glfwRestoreWindow(window());
                iconifyInProgress = false;
            }
        } finally {
            if (focusAttributeChanged) {
                GLFW.glfwSetWindowAttrib(window(), GLFW.GLFW_FOCUS_ON_SHOW, oldFocusOnShow);
                focusAttributeChanged = false;
            }
        }
    }

    private static long window() { return Minecraft.getInstance().getWindow().getWindow(); }

    private static void verifyMovie(String reason) throws Exception {
        Path movie = WaCameraClient.lastVideo();
        require(movie != null && !movie.equals(previousVideo) && Files.isRegularFile(movie) && Files.size(movie) > 1024,
                "No saved MP4 after " + reason);
    }

    private static void capture(String filename) throws Exception {
        try (var image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            image.writeToFile(WaCameraClient.root().resolve(filename));
        }
    }

    private static void cleanup() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        try {
            restoreWindow();
        } finally {
            forcedActive = null;
            mc.setWindowActive(GLFW.glfwGetWindowAttrib(window(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE);
            if (initialized) {
                WaCameraClient.stop(null);
                mc.setScreen(null);
                mc.options.pauseOnLostFocus = oldPauseOnLostFocus;
                var settings = WaCameraClient.settings();
                settings.quality = oldQuality;
                settings.audio = oldAudio;
                settings.ffmpeg = oldFfmpeg;
                settings.save(WaCameraClient.root());
                initialized = false;
            }
        }
    }

    private static void report(String message) throws Exception {
        Files.createDirectories(WaCameraClient.root());
        Files.writeString(WaCameraClient.root().resolve("smoke-background.txt"), message + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.getLogger("wacamera").log(System.Logger.Level.INFO, message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

