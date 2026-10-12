package dev.wacamera.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.wacamera.client.mixin.FogRendererAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** An extra world draw only: no game tick, picking, listener update, hand, HUD or display post chain. */
public final class DualPassRenderer implements AutoCloseable {
    private final TextureTarget target;
    private final Camera camera = new Camera();
    private final long intervalNanos;
    private long nextFrameNanos;
    private long renderedFrames;
    private int waterTarget = -1, waterPrevious = -1;
    private long waterChanged = -1;

    public DualPassRenderer(int width, int height, int fps) {
        this(width, height, fps, 1);
    }

    public DualPassRenderer(int width, int height, int fps, double playbackSpeed) {
        if (fps < 1 || !Double.isFinite(playbackSpeed) || playbackSpeed < 1 || playbackSpeed > 120)
            throw new IllegalArgumentException("Invalid recording frame rate or speed");
        target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
        intervalNanos = Math.max(1, Math.round(1_000_000_000.0 * playbackSpeed / fps));
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
    }

    public Camera camera() { return camera; }
    public RenderTarget target() { return target; }
    public long renderedFrames() { return renderedFrames; }
    public void resetCadence() { nextFrameNanos = 0; }
    public void startCadence(long firstFrameNanos) { nextFrameNanos = firstFrameNanos + intervalNanos; }

    public void render(GameRenderer renderer, DeltaTracker delta) {
        long now = System.nanoTime();
        if (now < nextFrameNanos) return;
        // Preserve the capture cadence across small display-frame timing variations.
        nextFrameNanos = nextFrameNanos == 0 ? now + intervalNanos
                : nextFrameNanos + ((now - nextFrameNanos) / intervalNanos + 1) * intervalNanos;
        Minecraft mc = Minecraft.getInstance();
        CaptureLevelRenderer levels = (CaptureLevelRenderer) mc.levelRenderer;
        int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        int targetFog = FogRendererAccessor.wacamera$getTargetBiomeFog();
        int previousFog = FogRendererAccessor.wacamera$getPreviousBiomeFog();
        long fogTime = FogRendererAccessor.wacamera$getBiomeChangedTime();
        RenderSystem.getModelViewStack().pushMatrix();
        boolean rendererStateSaved = false;
        mc.getProfiler().push("wacamera_capture");
        try {
            WaCameraClient.setRecordingPass(true);
            FogRendererAccessor.wacamera$setTargetBiomeFog(waterTarget);
            FogRendererAccessor.wacamera$setPreviousBiomeFog(waterPrevious);
            FogRendererAccessor.wacamera$setBiomeChangedTime(waterChanged);
            levels.wacamera$beginCapture();
            rendererStateSaved = true;
            target.bindWrite(true);
            float tick = delta.getGameTimeDeltaPartialTick(true);
            camera.setup(mc.level, mc.player, false, false, tick);
            if (!WaCameraClient.recordingFrameReady()) return;
            var pose = WaCameraClient.currentPose();
            Matrix4f view = new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf()));
            float far = Math.max(64, mc.options.getEffectiveRenderDistance() * 64);
            Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(pose.fov()),
                    target.width / (float) target.height, .05f, far);
            renderer.resetProjectionMatrix(projection);
            mc.levelRenderer.prepareCullFrustum(camera.getPosition(), view, projection);
            mc.levelRenderer.renderLevel(delta, false, camera, renderer, renderer.lightTexture(), view, projection);
            WaCameraClient.captureFrame();
            ++renderedFrames;
        } finally {
            if (rendererStateSaved) levels.wacamera$endCapture();
            WaCameraClient.setRecordingPass(false);
            waterTarget = FogRendererAccessor.wacamera$getTargetBiomeFog();
            waterPrevious = FogRendererAccessor.wacamera$getPreviousBiomeFog();
            waterChanged = FogRendererAccessor.wacamera$getBiomeChangedTime();
            FogRendererAccessor.wacamera$setTargetBiomeFog(targetFog);
            FogRendererAccessor.wacamera$setPreviousBiomeFog(previousFog);
            FogRendererAccessor.wacamera$setBiomeChangedTime(fogTime);
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDraw);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            mc.getProfiler().pop();
        }
    }

    @Override public void close() {
        // destroyBuffers unbinds framebuffers; a stop can run immediately before the normal world draw.
        int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int deleting = target.frameBufferId;
        int main = Minecraft.getInstance().getMainRenderTarget().frameBufferId;
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        try { target.destroyBuffers(); }
        finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead == deleting ? main : oldRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDraw == deleting ? main : oldDraw);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }
}
