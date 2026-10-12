package dev.wacamera.client;

import dev.wacamera.core.*;
import dev.wacamera.recorder.RecordingEngineInstaller;
import dev.wacamera.recorder.RecordingRecovery;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Short pages keep recording controls usable at normal Minecraft GUI scales. */
public final class ShotScreen extends Screen {
    private final Map<String, EditBox> fields = new LinkedHashMap<>();
    private final List<Label> labels = new ArrayList<>();
    private final List<ShotPoint> points = new ArrayList<>();
    private String name, dimension, interpolation, look;
    private double duration, playbackSpeed;
    private int countdown;
    private float fov;
    private Vec3d target;
    private int page, selected, savedIndex;
    private List<String> saved = List.of();
    private int patternSlot = 1;
    private List<ShotPatternRepository.Slot> patterns = List.of();
    private Button audioButton;
    private int recordingInfoX, recordingInfoY, recordingInfoWidth, recordingLeft, recordingWidth;
    private String message = "";
    private boolean installing;
    private boolean scanningRecovery;
    private int recoveryIndex;
    private List<RecordingRecovery.RecoverySession> recoveries = List.of();
    private record Label(String key, int x, int y, int width) { }

    public ShotScreen() {
        super(Component.translatable("wacamera.title"));
        apply(WaCameraClient.edited());
        refreshSaved();
        refreshPatterns();
        showDimensionNotice();
    }

    private void apply(ShotDefinition shot) {
        name = shot.name(); dimension = shot.dimension(); duration = shot.durationSeconds();
        playbackSpeed = shot.playbackSpeed();
        countdown = shot.countdownSeconds(); interpolation = shot.positionInterpolation(); look = shot.lookMode();
        fov = shot.fovDegrees(); target = shot.target() == null ? new Vec3d(0, 64, 0) : shot.target();
        points.clear(); points.addAll(shot.points()); selected = 0;
    }

    @Override protected void init() {
        fields.clear(); labels.clear(); clearWidgets(); audioButton = null;
        int left = Math.max(8, (width - 560) / 2), content = Math.min(width - 16, 560);
        int tabWidth = (content - 10) / 6;
        for (int i = 0; i < 6; i++) {
            final int next = i;
            Button tab = button("wacamera.tab." + i, left + i * (tabWidth + 2), 27, tabWidth, () -> changePage(next));
            tab.active = page != i;
        }
        switch (page) {
            case 0 -> shotPage(left, content);
            case 1 -> pointPage(left, content);
            case 2 -> lookPage(left, content);
            case 3 -> recordingPage(left, content);
            case 4 -> recoveryPage(left, content);
            case 5 -> patternPage(left, content);
            default -> throw new IllegalStateException();
        }
        int bottom = height - 25;
        int part = (content - 6) / 4;
        button("wacamera.preview", left, bottom, part, () -> begin(true))
                .setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("wacamera.preview_hint")));
        button("wacamera.record", left + part + 2, bottom, part, () -> begin(false))
                .setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("wacamera.record_hint")));
        button("wacamera.stop", left + (part + 2) * 2, bottom, part, () -> WaCameraClient.stop("wacamera.stopped"));
        button("gui.done", left + (part + 2) * 3, bottom, part, this::onClose);
    }

    private void shotPage(int x, int w) {
        field("name", name, x, 54, w - 80);
        button("wacamera.new_shot", x + w - 74, 65, 74, this::newShot);
        int third = (w - 12) / 3;
        field("duration", text(duration), x, 88, third);
        field("countdown", Integer.toString(countdown), x + third + 6, 88, third);
        button("wacamera.interpolation." + interpolation, x + (third + 6) * 2, 98, third, () -> {
            if (!commit()) return; interpolation = interpolation.equals("linear") ? "smooth" : "linear"; init();
        });
        button("wacamera.save", x, 130, (w - 6) / 2, this::save);
        button("wacamera.load", x + (w + 6) / 2, 130, (w - 6) / 2, this::load);
        String file = saved.isEmpty() ? Component.translatable("wacamera.no_saved").getString() : saved.get(Math.min(savedIndex, saved.size() - 1));
        buttonText("<", x, 156, 24, () -> { if (!saved.isEmpty() && commit()) { savedIndex = Math.floorMod(savedIndex - 1, saved.size()); init(); } });
        buttonText(file, x + 28, 156, w - 56, () -> { if (!saved.isEmpty() && commit()) { savedIndex = (savedIndex + 1) % saved.size(); init(); } });
        buttonText(">", x + w - 24, 156, 24, () -> { if (!saved.isEmpty() && commit()) { savedIndex = (savedIndex + 1) % saved.size(); init(); } });
    }

    private void pointPage(int x, int w) {
        selected = Math.max(0, Math.min(selected, points.size() - 1));
        ShotPoint p = points.get(selected);
        buttonText("<", x, 53, 28, () -> select(-1));
        buttonText(Component.translatable("wacamera.point_index", selected + 1, points.size()).getString(), x + 32, 53, w - 64, () -> select(1));
        buttonText(">", x + w - 28, 53, 28, () -> select(1));
        int third = (w - 12) / 3;
        field("x", text(p.x()), x, 78, third);
        field("y", text(p.y()), x + third + 6, 78, third);
        field("z", text(p.z()), x + (third + 6) * 2, 78, third);
        field("yaw", text(p.yawDegrees()), x, 112, third);
        field("pitch", text(p.pitchDegrees()), x + third + 6, 112, third);
        button("wacamera.use_view", x + (third + 6) * 2, 122, third, () -> {
            if (!commit()) return; points.set(selected, WaCameraClient.pointHere()); init();
        });
        int part = (w - 9) / 4;
        button("wacamera.add", x, 156, part, () -> {
            if (!commit()) return;
            if (points.size() >= 64) { message = Component.translatable("wacamera.error.points").getString(); return; }
            points.add(selected + 1, WaCameraClient.pointHere()); selected++; init();
        });
        button("wacamera.remove", x + part + 3, 156, part, () -> {
            if (!commit()) return;
            if (points.size() > 1) { points.remove(selected); selected = Math.min(selected, points.size() - 1); init(); }
        });
        button("wacamera.move_up", x + (part + 3) * 2, 156, part, () -> move(-1));
        button("wacamera.move_down", x + (part + 3) * 3, 156, part, () -> move(1));
    }

    private void lookPage(int x, int w) {
        button("wacamera.look." + look, x, 55, w, () -> {
            if (!commit()) return;
            look = switch (look) { case "registered" -> "self"; case "self" -> "target"; default -> "registered"; };
            init();
        });
        field("fov", text(fov), x, 85, w);
        int third = (w - 12) / 3;
        field("target_x", text(target.x()), x, 119, third);
        field("target_y", text(target.y()), x + third + 6, 119, third);
        field("target_z", text(target.z()), x + (third + 6) * 2, 119, third);
        button("wacamera.target_self", x, 157, w, () -> { if (commit()) { target = WaCameraClient.selfTarget(1); init(); } });
    }

    private void recordingPage(int x, int w) {
        ClientSettings s = WaCameraClient.settings();
        int half = (w - 6) / 2;
        field("ffmpeg", s.ffmpeg, x, 53, half).setMaxLength(1024);
        field("audio_helper", s.audioHelper, x + half + 6, 53, half).setMaxLength(1024);
        buttonText(s.quality, x, 88, half, () -> {
            if (!commit()) return;
            s.quality = switch (s.quality) {
                case "720p24" -> "720p30"; case "720p30" -> "1080p24";
                case "1080p24" -> "1080p30"; case "1080p30" -> "1080p60"; default -> "720p24";
            };
            init();
        });
        audioButton = button("wacamera.audio." + s.audio, x + half + 6, 88, half,
                () -> { if (commit()) { s.audio = !s.audio; init(); } });
        audioButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("wacamera.speed_audio_hint")));
        int speedWidth = Math.min((w - 12) / 3, 140);
        field("playback_speed", speedText(playbackSpeed), x, 113, speedWidth)
                .setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("wacamera.speed_hint")));
        recordingInfoX = x + speedWidth + 8; recordingInfoY = 116;
        recordingInfoWidth = w - speedWidth - 8; recordingLeft = x; recordingWidth = w;
        button("wacamera.open_folder", x, 159, half, WaCameraClient::openOutputFolder);
        Button install = button("wacamera.prepare_engine", x + half + 6, 159, half, this::prepareEngine);
        install.active = !installing;
    }

    private void patternPage(int x, int w) {
        int cell = (w - 16) / 5;
        for (int slot = 1; slot <= ShotPatternRepository.SLOT_COUNT; slot++) {
            int number = slot;
            var savedSlot = pattern(number);
            String caption = slotLabel(number) + (savedSlot != null && savedSlot.occupied() ? " *" : "");
            Button choose = buttonText(caption, x + ((slot - 1) % 5) * (cell + 4), 54 + ((slot - 1) / 5) * 24, cell,
                    () -> { if (commit()) { patternSlot = number; refreshPatterns(); init(); } });
            choose.active = number != patternSlot;
            choose.setTooltip(net.minecraft.client.gui.components.Tooltip.create(savedSlot != null && savedSlot.occupied()
                    ? Component.literal(savedSlot.name()) : Component.translatable("wacamera.pattern_empty")));
        }
        field("name", name, x, 106, w).setMaxLength(80);
        var chosen = pattern(patternSlot);
        String status = chosen != null && chosen.occupied()
                ? Component.translatable("wacamera.pattern_selected", slotLabel(patternSlot), chosen.name()).getString()
                : Component.translatable("wacamera.pattern_selected_empty", slotLabel(patternSlot)).getString();
        buttonText(status, x, 139, w, () -> { }).active = false;
        button("wacamera.pattern_save", x, 165, (w - 6) / 2, this::savePattern);
        Button load = button("wacamera.pattern_load", x + (w + 6) / 2, 165, (w - 6) / 2, this::loadPattern);
        load.active = chosen != null && chosen.occupied();
    }

    private static String slotLabel(int number) { return number + " / " + (char) ('A' + number - 1); }
    private ShotPatternRepository.Slot pattern(int number) {
        return patterns.stream().filter(slot -> slot.number() == number).findFirst().orElse(null);
    }
    private boolean refreshPatterns() {
        try { patterns = WaCameraClient.patterns().list(); return true; }
        catch (Exception e) { patterns = List.of(); message = e.getMessage(); return false; }
    }
    private void savePattern() {
        if (!commit()) return;
        if (!refreshPatterns()) return;
        ShotDefinition value = definition();
        int slot = patternSlot;
        Runnable save = () -> {
            try {
                WaCameraClient.patterns().save(slot, value);
                WaCameraClient.setEdited(value);
                refreshPatterns();
                message = Component.translatable("wacamera.pattern_saved", slotLabel(slot), value.name()).getString();
                init();
            } catch (Exception e) { message = e.getMessage(); }
        };
        var previous = pattern(slot);
        if (previous != null && previous.occupied()) confirm("wacamera.pattern_overwrite_title",
                Component.translatable("wacamera.pattern_overwrite", slotLabel(slot), previous.name(), value.name()), save);
        else save.run();
    }
    private void loadPattern() {
        if (!commit()) return;
        try { confirmLoad(WaCameraClient.patterns().load(patternSlot)); }
        catch (Exception e) { message = e.getMessage(); }
    }
    private void confirmLoad(ShotDefinition value) {
        Runnable load = () -> {
            apply(value); WaCameraClient.setEdited(value);
            message = Component.translatable("wacamera.settings_loaded", value.name()).getString();
            showDimensionNotice(); init();
        };
        if (value.equals(definition())) load.run();
        else confirm("wacamera.load_confirm_title", Component.translatable("wacamera.load_confirm", value.name()), load);
    }
    private void confirm(String titleKey, Component question, Runnable action) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        mc.setScreen(new ConfirmScreen(accepted -> {
            mc.setScreen(this);
            if (accepted) guarded(action);
        }, Component.translatable(titleKey), question));
    }

    private void recoveryPage(int x, int w) {
        recoveryIndex = Math.min(recoveryIndex, Math.max(0, recoveries.size() - 1));
        var selectedSession = recoveries.isEmpty() ? null : recoveries.get(recoveryIndex);
        buttonText("<", x, 55, 26, () -> { if (!recoveries.isEmpty()) { recoveryIndex = Math.floorMod(recoveryIndex - 1, recoveries.size()); init(); } });
        String title = selectedSession == null ? Component.translatable("wacamera.recovery_none").getString() : selectedSession.directory().getFileName().toString();
        buttonText(title, x + 30, 55, w - 60, () -> { });
        buttonText(">", x + w - 26, 55, 26, () -> { if (!recoveries.isEmpty()) { recoveryIndex = (recoveryIndex + 1) % recoveries.size(); init(); } });
        Button refresh = button("wacamera.recovery_scan", x, 83, w, this::scanRecovery);
        refresh.active = !scanningRecovery;
        Button normal = button("wacamera.recover", x, 114, (w - 6) / 2, () -> recover(false));
        Button silent = button("wacamera.recover_silent", x + (w + 6) / 2, 114, (w - 6) / 2, () -> recover(true));
        normal.active = silent.active = selectedSession != null && !selectedSession.active() && !WaCameraClient.busy();
        if (selectedSession != null) {
            String details = selectedSession.status() + " / " + Component.translatable("wacamera.audio." + selectedSession.audioRequested()).getString();
            if (selectedSession.audioRequested() && !selectedSession.audioFilesPresent()) details += " / " + Component.translatable("wacamera.recovery_audio_missing").getString();
            if (selectedSession.active()) details += " / " + Component.translatable("wacamera.recovery_active").getString();
            buttonText(details, x, 144, w, () -> { }).active = false;
        }
    }

    private void scanRecovery() {
        if (scanningRecovery) return;
        scanningRecovery = true;
        message = Component.translatable("wacamera.recovery_scanning").getString();
        init();
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return RecordingRecovery.listRecoverySessions(WaCameraClient.root().resolve("recordings")); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }).whenComplete((result, error) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
            scanningRecovery = false;
            if (error != null) { WaCameraClient.error(error); message = WaCameraClient.status(); }
            else { recoveries = result; message = ""; }
            if (net.minecraft.client.Minecraft.getInstance().screen == this && page == 4) init();
        }));
    }

    private void recover(boolean silent) {
        if (recoveries.isEmpty() || WaCameraClient.busy()) return;
        var session = recoveries.get(recoveryIndex);
        if (session.active()) return;
        message = Component.translatable("wacamera.saving").getString();
        WaCameraClient.recover(session.directory(), silent).whenComplete((path, error) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
            if (error != null) { WaCameraClient.error(error); message = WaCameraClient.status(); }
            else message = Component.translatable("wacamera.saved", path.getFileName().toString()).getString();
            if (net.minecraft.client.Minecraft.getInstance().screen == this && page == 4) scanRecovery();
        }));
        init();
    }

    private EditBox field(String key, String value, int x, int y, int w) {
        labels.add(new Label("wacamera.field." + key, x, y, w));
        EditBox box = new EditBox(font, x, y + 11, w, 18, Component.translatable("wacamera.field." + key));
        box.setMaxLength(1024);
        box.setValue(value);
        box.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("wacamera.field." + key)));
        fields.put(key, box);
        addRenderableWidget(box);
        return box;
    }

    private Button button(String key, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.translatable(key), ignored -> guarded(action)).bounds(x, y, w, 20).build());
    }
    private Button buttonText(String text, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), ignored -> guarded(action)).bounds(x, y, w, 20).build());
    }
    private void guarded(Runnable action) {
        try { action.run(); }
        catch (RuntimeException e) { message = e.getMessage() == null ? e.toString() : e.getMessage(); }
    }
    private static String text(double value) { return String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", ""); }
    private static String speedText(double value) { return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    private double number(String key) {
        double n = Double.parseDouble(fields.get(key).getValue().trim());
        if (!Double.isFinite(n)) throw new IllegalArgumentException("Finite numbers required: " + key);
        return n;
    }

    private boolean commit() {
        try {
            String nextName = name;
            double nextDuration = duration, nextSpeed = playbackSpeed;
            int nextCountdown = countdown;
            float nextFov = fov;
            Vec3d nextTarget = target;
            List<ShotPoint> nextPoints = new ArrayList<>(points);
            switch (page) {
                case 0 -> {
                    nextName = fields.get("name").getValue().trim(); nextDuration = unchangedNumber("duration", duration);
                    nextCountdown = Integer.parseInt(fields.get("countdown").getValue().trim());
                }
                case 1 -> {
                    ShotPoint old = points.get(selected);
                    double pitch = unchangedNumber("pitch", old.pitchDegrees());
                    if (pitch < -90 || pitch > 90) throw new IllegalArgumentException("Pitch must be between -90 and 90");
                    nextPoints.set(selected, new ShotPoint(old.id(), unchangedNumber("x", old.x()), unchangedNumber("y", old.y()),
                            unchangedNumber("z", old.z()), (float)unchangedNumber("yaw", old.yawDegrees()), (float)pitch));
                }
                case 2 -> {
                    nextFov = (float)unchangedNumber("fov", fov);
                    nextTarget = new Vec3d(unchangedNumber("target_x", target.x()), unchangedNumber("target_y", target.y()), unchangedNumber("target_z", target.z()));
                }
                case 3 -> nextSpeed = number("playback_speed");
                case 5 -> nextName = fields.get("name").getValue().trim();
            }
            ShotDefinition value = new ShotDefinition(ShotDefinition.CURRENT_SCHEMA_VERSION, nextName, dimension,
                    nextDuration, nextCountdown, interpolation, "constant", look, nextFov, nextPoints,
                    look.equals("target") ? nextTarget : null, nextSpeed);
            // Keep the last valid model intact when an edit is incomplete or invalid.
            name = nextName; duration = nextDuration; countdown = nextCountdown; fov = nextFov;
            target = nextTarget; playbackSpeed = nextSpeed; points.clear(); points.addAll(nextPoints);
            if (page == 3) {
                var settings = WaCameraClient.settings();
                settings.ffmpeg = fields.get("ffmpeg").getValue().trim(); settings.audioHelper = fields.get("audio_helper").getValue().trim();
            }
            if (!value.equals(WaCameraClient.edited())) WaCameraClient.setEdited(value);
            message = "";
            return true;
        } catch (RuntimeException e) { message = e.getMessage() == null ? "Invalid input" : e.getMessage(); return false; }
    }
    // Display rounding must not alter a saved coordinate merely by opening its tab.
    private double unchangedNumber(String key, double original) {
        return fields.get(key).getValue().trim().equals(text(original)) ? original : number(key);
    }
    private ShotDefinition definition() { return new ShotDefinition(ShotDefinition.CURRENT_SCHEMA_VERSION, name, dimension, duration, countdown, interpolation, "constant", look, fov, List.copyOf(points), look.equals("target") ? target : null, playbackSpeed); }
    private void changePage(int next) { if (commit()) { page = next; if (page == 5) refreshPatterns(); init(); if (page == 4) scanRecovery(); } }
    private void select(int delta) { if (commit()) { selected = Math.floorMod(selected + delta, points.size()); init(); } }
    private void move(int delta) {
        if (!commit()) return;
        int next = selected + delta;
        if (next >= 0 && next < points.size()) { Collections.swap(points, selected, next); selected = next; init(); }
    }
    private void refreshSaved() {
        try { saved = WaCameraClient.repository().list(); savedIndex = Math.min(savedIndex, Math.max(0, saved.size() - 1)); }
        catch (Exception e) { message = e.getMessage(); }
    }
    private void save() {
        if (!commit()) return;
        try { WaCameraClient.repository().save(definition()); WaCameraClient.setEdited(definition()); WaCameraClient.settings().save(WaCameraClient.root()); refreshSaved(); message = Component.translatable("wacamera.shot_saved").getString(); init(); }
        catch (Exception e) { message = e.getMessage(); }
    }
    private void newShot() {
        if (!commit()) return;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return;
        try {
            // Preserve the previous draft under a unique name; never retarget its dimension silently.
            ShotDefinition previous = definition();
            String draftName = previous.name().substring(0, Math.min(55, previous.name().length())) + "-draft-" + UUID.randomUUID().toString().substring(0, 8);
            WaCameraClient.repository().save(new ShotDefinition(ShotDefinition.CURRENT_SCHEMA_VERSION, draftName, previous.dimension(), previous.durationSeconds(), previous.countdownSeconds(), previous.positionInterpolation(), previous.speedMode(), previous.lookMode(), previous.fovDegrees(), previous.points(), previous.target(), previous.playbackSpeed()));
            apply(new ShotDefinition(ShotDefinition.CURRENT_SCHEMA_VERSION, "Shot-" + UUID.randomUUID().toString().substring(0, 8), mc.level.dimension().location().toString(), 10, 3, "linear", "constant", "registered", 70, List.of(WaCameraClient.pointHere()), null, previous.playbackSpeed()));
            WaCameraClient.setEdited(definition());
            refreshSaved();
            message = Component.translatable("wacamera.new_created", draftName).getString();
            init();
        } catch (Exception e) { message = e.getMessage(); }
    }
    private void showDimensionNotice() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null && !dimension.equals(mc.level.dimension().location().toString())) message = Component.translatable("wacamera.different_dimension").getString();
    }
    private void load() {
        if (saved.isEmpty() || !commit()) return;
        try { confirmLoad(WaCameraClient.repository().load(saved.get(savedIndex))); }
        catch (Exception e) { message = e.getMessage(); }
    }
    private void begin(boolean preview) {
        if (!commit()) return;
        try { WaCameraClient.start(definition(), preview); }
        catch (Exception e) {
            if (net.minecraft.client.Minecraft.getInstance().screen == null) net.minecraft.client.Minecraft.getInstance().setScreen(this);
            message = e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }
    private void prepareEngine() {
        if (installing || !commit()) return;
        installing = true;
        message = Component.translatable("wacamera.engine_downloading").getString();
        init();
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return RecordingEngineInstaller.prepareWindows(WaCameraClient.root().resolve("tools")); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }).whenComplete((path, error) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
            installing = false;
            if (net.minecraft.client.Minecraft.getInstance().screen == this) commit();
            if (error != null) {
                WaCameraClient.error(error);
                message = WaCameraClient.status();
            } else {
                WaCameraClient.settings().ffmpeg = path.toString();
                try { WaCameraClient.settings().save(WaCameraClient.root()); }
                catch (Exception e) { WaCameraClient.error(e); }
                message = Component.translatable("wacamera.engine_ready").getString();
            }
            if (net.minecraft.client.Minecraft.getInstance().screen == this && page == 3) init();
        }));
    }
    @Override public void onClose() {
        if (!commit()) return;
        try { WaCameraClient.settings().save(WaCameraClient.root()); }
        catch (Exception e) { WaCameraClient.error(e); }
        super.onClose();
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void resize(net.minecraft.client.Minecraft mc, int width, int height) {
        Map<String, String> editing = new LinkedHashMap<>();
        fields.forEach((key, box) -> editing.put(key, box.getValue()));
        super.resize(mc, width, height);
        editing.forEach((key, value) -> { if (fields.containsKey(key)) fields.get(key).setValue(value); });
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
        graphics.fill(0, 0, width, height, 0xE0151922);
        graphics.drawCenteredString(font, title, width / 2, 9, 0xFFFFFF);
        for (Label label : labels) graphics.drawString(font, font.plainSubstrByWidth(Component.translatable(label.key).getString(), label.width), label.x, label.y, 0xBBD1E5, false);
        if (page == 3) renderRecordingSummary(graphics);
        super.render(graphics, mouseX, mouseY, tick);
        String line = message.isBlank() ? WaCameraClient.status() : message;
        if (!line.isBlank()) graphics.drawString(font, font.plainSubstrByWidth(line, width - 16), 8, height - 38, 0xFFCC77, false);
    }

    private void renderRecordingSummary(GuiGraphics graphics) {
        double speed;
        try { speed = number("playback_speed"); }
        catch (RuntimeException invalid) { speed = Double.NaN; }
        boolean valid = Double.isFinite(speed) && speed >= 1 && speed <= 120;
        boolean silent = valid && speed > 1;
        var settings = WaCameraClient.settings();
        audioButton.active = !silent;
        audioButton.setMessage(Component.translatable(silent ? "wacamera.audio.speed_disabled" : "wacamera.audio." + settings.audio));
        int fps = settings.quality.endsWith("24") ? 24 : settings.quality.endsWith("60") ? 60 : 30;
        if (valid) {
            drawRecordingLine(graphics, Component.translatable("wacamera.capture_rate", fps, speedText(speed), text(fps / speed)), recordingInfoX, recordingInfoY, recordingInfoWidth);
            drawRecordingLine(graphics, Component.translatable("wacamera.output_duration", text(duration), speedText(speed), text(duration / speed)), recordingInfoX, recordingInfoY + 12, recordingInfoWidth);
        } else drawRecordingLine(graphics, Component.translatable("wacamera.speed_invalid"), recordingInfoX, recordingInfoY, recordingInfoWidth);
        if (silent) drawRecordingLine(graphics, Component.translatable("wacamera.speed_audio_hint"), recordingLeft, 146, recordingWidth);
    }
    private void drawRecordingLine(GuiGraphics graphics, Component line, int x, int y, int w) {
        graphics.drawString(font, font.plainSubstrByWidth(line.getString(), w), x, y, 0xBBD1E5, false);
    }
}
