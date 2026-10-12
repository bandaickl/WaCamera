package dev.wacamera.client;

import dev.wacamera.core.CameraPose;
import dev.wacamera.core.ShotDefinition;
import dev.wacamera.core.ShotPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.sounds.SoundSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Explicit development fixture: only the newly created, muted smoke world is used. */
final class MarkerSmokeTestRunner {
    private static int phase, recordingIndex, samples, betweenCaptures;
    private static long lastCaptureFrames;
    private static CameraPose lastMarkerPose;
    private static long started, activeAt, firstDraw, hiddenDraw, hiddenAt, due;
    private static CameraPose firstPose;
    private static double maximumMovement;
    private static boolean screenshot, hiddenChecked, savedSettings;
    private static String oldQuality, oldFfmpeg;
    private static boolean oldAudio;
    private static Path previous;
    private static ShotDefinition shot;
    static boolean enabled() { return Boolean.getBoolean("wacamera.smokeMarker"); }

    static void tick() throws Exception {
        var mc = Minecraft.getInstance();
        mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
        long now = System.nanoTime();
        if (started == 0) started = now;
        try {
            require(now-started < 100_000_000_000L, "Marker test timed out: " + phase);
            switch (phase) {
                case 0 -> {
                    if (Boolean.getBoolean("wacamera.smokeShaders")) {
                        require(ShaderCompatibility.isShaderActive(), "Expected an active shader pack");
                        report("Shader API confirms active pack for marker test");
                    }
                    require(mc.getSingleplayerServer() != null
                            && mc.getSingleplayerServer().getWorldData().getLevelName().startsWith("WaCamera-smoke-"),
                            "Not an isolated test world");
                    require(WaCameraClient.markerPose(1) == null, "Idle marker must be absent");
                    var settings = WaCameraClient.settings();
                    oldQuality=settings.quality; oldAudio=settings.audio; oldFfmpeg=settings.ffmpeg; savedSettings=true;
                    settings.audio=false;
                    settings.ffmpeg=System.getProperty("wacamera.smokeFfmpeg", settings.ffmpeg);
                    mc.player.setYRot(0); mc.player.setXRot(0);
                    mc.gui.getChat().clearMessages(false);
                    start();
                    phase=1;
                }
                case 1 -> {
                    if (WaCameraClient.state() == WaCameraClient.State.RECORDING) {
                        if (activeAt == 0) activeAt=now;
                        var captured=WaCameraClient.currentPose();
                        var pose = WaCameraClient.markerPose(1);
                        require(captured.equals(WaCameraClient.currentPose()), "Guide sampling altered capture pose");
                        if (!mc.options.hideGui) {
                            require(pose != null, "Recording marker pose absent");
                            if (firstPose == null) firstPose=pose;
                            maximumMovement=Math.max(maximumMovement, firstPose.position().distanceTo(pose.position()));
                            long frames=WaCameraClient.recordedRenderFrames();
                            if (lastMarkerPose!=null && frames==lastCaptureFrames
                                    && lastMarkerPose.position().distanceTo(pose.position())>.001) betweenCaptures++;
                            lastCaptureFrames=frames; lastMarkerPose=pose;
                        } else require(pose == null, "F1 should hide guide");
                        var camera=mc.gameRenderer.getMainCamera();
                        require(mc.options.getCameraType().isFirstPerson()
                                && mc.getCameraEntity()==mc.player && !camera.isDetached()
                                && camera.getPosition().distanceTo(mc.player.getEyePosition()) < 1.2,
                                "Marker changed player camera");
                        // The guide API must not mutate the recording pose or leak into an offscreen pass.
                        WaCameraClient.setRecordingPass(true);
                        try { require(WaCameraClient.markerPose(1)==null, "Guide leaked into recording pass"); }
                        finally { WaCameraClient.setRecordingPass(false); }
                        require(captured.equals(WaCameraClient.currentPose()), "Guide sampling altered capture state");
                        samples++;
                        if (!screenshot && now-activeAt > 1_300_000_000L) {
                            require(CameraMarkerRenderer.renderedMarkers() > firstDraw+5, "No player-side marker draws");
                            capture("smoke-marker-player-" + recordingIndex + ".png");
                            screenshot=true;
                        }
                        if (recordingIndex==0 && !hiddenChecked && now-activeAt > 2_300_000_000L) {
                            if (hiddenAt==0) {
                                mc.options.hideGui=true; hiddenAt=now;
                                hiddenDraw=CameraMarkerRenderer.renderedMarkers();
                            } else if (now-hiddenAt > 500_000_000L) {
                                require(CameraMarkerRenderer.renderedMarkers()==hiddenDraw, "F1 still drew marker");
                                capture("smoke-marker-hidden.png");
                                mc.options.hideGui=false; hiddenChecked=true;
                            }
                        }
                    }
                    if (WaCameraClient.state()!=WaCameraClient.State.IDLE) return;
                    require(activeAt!=0 && screenshot && samples>30, "Recording did not run");
                    require(WaCameraClient.lastVideo()!=null && !WaCameraClient.lastVideo().equals(previous), "No marker-test movie");
                    require(maximumMovement>3, "Guide failed to follow path between capture frames");
                    if(recordingIndex==1) require(betweenCaptures>5, "Guide only updates when capturing");
                    require(CameraMarkerRenderer.renderedCaptureMarkers()==0, "Marker was drawn into capture");
                    require(WaCameraClient.markerPose(1)==null, "Guide remains after stop");
                    report("PASS speed=" + shot.playbackSpeed() + ", seconds=" + shot.durationSeconds()
                            + ", normalCameraChecks=" + samples + ", guideDraws=" + (CameraMarkerRenderer.renderedMarkers()-firstDraw)
                            + ", movement=" + maximumMovement + ", betweenCaptures=" + betweenCaptures
                            + ", captureMarkerDraws=" + CameraMarkerRenderer.renderedCaptureMarkers()
                            + ", video=" + WaCameraClient.lastVideo());
                    firstDraw=CameraMarkerRenderer.renderedMarkers();
                    due=now+700_000_000L; phase=2;
                }
                case 2 -> {
                    if(now<due) return;
                    require(CameraMarkerRenderer.renderedMarkers()==firstDraw, "Guide drew while idle");
                    capture("smoke-marker-stopped-" + recordingIndex + ".png");
                    if(recordingIndex++==0) { start(); phase=1; }
                    else {
                        firstDraw=CameraMarkerRenderer.renderedMarkers();
                        WaCameraClient.start(shot, true);
                        due=now+800_000_000L; phase=3;
                    }
                }
                case 3 -> {
                    require(WaCameraClient.markerPose(1)==null, "Guide visible in preview");
                    if(now<due) return;
                    require(CameraMarkerRenderer.renderedMarkers()==firstDraw, "Guide drew into preview");
                    WaCameraClient.stop("wacamera.stopped");
                    cleanup();
                    report("PASS: moving camera guide, normal and timelapse, capture exclusion, F1, idle and preview");
                    phase=4; mc.stop();
                }
                default -> { }
            }
        } catch(Exception e) {
            try { cleanup(); } catch(Exception cleanup) { e.addSuppressed(cleanup); }
            report("FAILED: " + e);
            throw e;
        }
    }
    private static void start() throws Exception {
        var mc=Minecraft.getInstance();
        var p=mc.player;
        double speed=recordingIndex==0?1:10, duration=recordingIndex==0?6:8;
        WaCameraClient.settings().quality=recordingIndex==0?"720p30":"720p24";
        shot=new ShotDefinition(ShotDefinition.CURRENT_SCHEMA_VERSION, "marker-smoke-" + recordingIndex,
                mc.level.dimension().location().toString(),duration,1,"linear","constant",recordingIndex==0?"self":"registered",70,
                List.of(new ShotPoint("a",p.getX()+2,p.getEyeY()+.6,p.getZ()+7,70,10),
                        new ShotPoint("b",p.getX()-2,p.getEyeY()+.6,p.getZ()+7,70,10)),null,speed);
        previous=WaCameraClient.lastVideo();
        activeAt=0; samples=0; maximumMovement=0; firstPose=null; screenshot=false;
        betweenCaptures=0; lastMarkerPose=null; lastCaptureFrames=-1;
        firstDraw=CameraMarkerRenderer.renderedMarkers();
        WaCameraClient.start(shot,false);
    }
    private static void capture(String name) throws Exception {
        try(var image=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            image.writeToFile(WaCameraClient.root().resolve(name));
            if(name.startsWith("smoke-marker-player-")) {
                int cyan=0;
                for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
                    int pixel=image.getPixelRGBA(x,y);
                    int r=pixel&255, g=(pixel>>>8)&255, b=(pixel>>>16)&255;
                    if(r<100 && g>150 && b>180 && g>r+70) cyan++;
                }
                require(cyan>150,"Guide draw ran but no visible cyan guide pixels: "+cyan);
                report("Visible guide pixel check: "+name+" cyan="+cyan);
            }
        }
    }
    private static void cleanup() throws Exception {
        Minecraft.getInstance().options.hideGui=false;
        if(savedSettings) {
            var s=WaCameraClient.settings();
            s.quality=oldQuality; s.audio=oldAudio; s.ffmpeg=oldFfmpeg;
            s.save(WaCameraClient.root()); savedSettings=false;
        }
    }
    private static void report(String message) throws Exception {
        Files.createDirectories(WaCameraClient.root());
        Files.writeString(WaCameraClient.root().resolve("smoke-marker.txt"),message+System.lineSeparator(),
                StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        System.getLogger("wacamera").log(System.Logger.Level.INFO,message);
    }
    private static void require(boolean okay,String message) { if(!okay) throw new IllegalStateException(message); }
}

