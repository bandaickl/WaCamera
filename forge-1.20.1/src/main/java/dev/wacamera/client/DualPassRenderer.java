package dev.wacamera.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.wacamera.client.mixin.FogRendererAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/** An extra world draw only: no game tick, picking, listener update, hand, HUD or display post chain. */
public final class DualPassRenderer implements AutoCloseable {
    private final TextureTarget target;
    private final Camera camera = new Camera();
    private final ShaderCapturePipeline shaders = new ShaderCapturePipeline();
    private final ShaderCaptureState shaderState = new ShaderCaptureState();
    private final DistantHorizonCaptureBuffers distantHorizons = new DistantHorizonCaptureBuffers();
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

    public void render(GameRenderer renderer, float partialTick, long finishTime) {
        long now = System.nanoTime();
        if (now + 500_000L < nextFrameNanos) return;
        nextFrameNanos = nextFrameNanos == 0 ? now + intervalNanos
                : nextFrameNanos + Math.max(1, (now - nextFrameNanos) / intervalNanos + 1) * intervalNanos;
        Minecraft mc = Minecraft.getInstance();
        ShaderCapturePipeline.prepareFrame();
        CaptureLevelRenderer levels = (CaptureLevelRenderer) mc.levelRenderer;
        int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        boolean scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] scissorBox = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        Matrix3f inverseView = new Matrix3f(RenderSystem.getInverseViewRotationMatrix());
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var previousSorting = RenderSystem.getVertexSorting();
        int previousWaterTarget = FogRendererAccessor.wacamera$getTarget();
        int previousWaterFog = FogRendererAccessor.wacamera$getPrevious();
        long previousWaterChanged = FogRendererAccessor.wacamera$getChanged();
        RenderSystem.getModelViewStack().pushPose();
        boolean rendererStateSaved = false;
        mc.getProfiler().push("wacamera_capture");
        try {
            WaCameraClient.setRecordingPass(true);
            FogRendererAccessor.wacamera$setTarget(waterTarget);
            FogRendererAccessor.wacamera$setPrevious(waterPrevious);
            FogRendererAccessor.wacamera$setChanged(waterChanged);
            levels.wacamera$beginCapture();
            rendererStateSaved = true;
            EmbeddiumCompatibility.beginCapture();
            distantHorizons.beginFrame();
            // The display may leave a window-sized scissor active. The recording target can be larger.
            RenderSystem.disableScissor();
            target.bindWrite(true);
            // CameraMixin sets detached after applying the pose; avoid third-person collision probes here.
            camera.setup(mc.level, mc.player, false, false, partialTick);
            if (!WaCameraClient.recordingFrameReady()) return;
            if (ShaderCompatibility.isShaderActive()) shaderState.enter();
            if (shaders.enter()) {
                // Compiling the first shader pass can take time. Timestamp the actual frame after compilation.
                camera.setup(mc.level, mc.player, false, false, partialTick);
                if (!WaCameraClient.recordingFrameReady()) return;
            }
            var pose = WaCameraClient.currentPose();
            PoseStack view = new PoseStack();
            view.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
            view.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180));
            float far = Math.max(64, mc.options.getEffectiveRenderDistance() * 64);
            Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(pose.fov()),
                    target.width / (float) target.height, .05f, far);
            renderer.resetProjectionMatrix(projection);
            RenderSystem.setInverseViewRotationMatrix(new Matrix3f(view.last().normal()).invert());
            mc.levelRenderer.prepareCullFrustum(view, camera.getPosition(), projection);
            mc.levelRenderer.renderLevel(view, partialTick, finishTime, false, camera, renderer, renderer.lightTexture(), projection);
            shaders.finish();
            WaCameraClient.captureFrame();
            ++renderedFrames;
        } finally {
            try {
                try {
                    try { distantHorizons.endFrame(); }
                    finally {
                        try { shaders.exit(); }
                        finally { shaderState.exit(); }
                    }
                } finally {
                    try { if (rendererStateSaved) levels.wacamera$endCapture(); }
                    finally { EmbeddiumCompatibility.endCapture(); }
                }
            } finally {
                WaCameraClient.setRecordingPass(false);
                RenderSystem.getModelViewStack().popPose();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
                RenderSystem.setInverseViewRotationMatrix(inverseView);
                waterTarget = FogRendererAccessor.wacamera$getTarget();
                waterPrevious = FogRendererAccessor.wacamera$getPrevious();
                waterChanged = FogRendererAccessor.wacamera$getChanged();
                FogRendererAccessor.wacamera$setTarget(previousWaterTarget);
                FogRendererAccessor.wacamera$setPrevious(previousWaterFog);
                FogRendererAccessor.wacamera$setChanged(previousWaterChanged);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDraw);
                RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
                if (scissorEnabled) RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
                else RenderSystem.disableScissor();
                mc.getProfiler().pop();
            }
        }
    }

    @Override public void close() {
        int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int deleting = target.frameBufferId;
        int main = Minecraft.getInstance().getMainRenderTarget().frameBufferId;
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        try {
            try {
                try { distantHorizons.close(); }
                finally { shaders.close(); }
            }
            finally { target.destroyBuffers(); }
        }
        finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead == deleting ? main : oldRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDraw == deleting ? main : oldDraw);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }
}
