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
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/** An extra world draw only: no game tick, picking, listener update, hand, HUD or display post chain. */
public final class DualPassRenderer implements AutoCloseable {
    private final TextureTarget target;
    private final Camera camera = new Camera();
    private final ShaderCapturePipeline shaders = new ShaderCapturePipeline();
    private final ShaderCaptureState shaderState = new ShaderCaptureState();
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
        ShaderCapturePipeline.prepareFrame();
        CaptureLevelRenderer levels = (CaptureLevelRenderer) mc.levelRenderer;
        RenderState normal = new RenderState();
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
            SodiumCompatibility.beginCapture();
            RenderSystem.disableScissor();
            target.bindWrite(true);
            float tick = delta.getGameTimeDeltaPartialTick(true);
            camera.setup(mc.level, mc.player, false, false, tick);
            if (!WaCameraClient.recordingFrameReady()) return;
            if (ShaderCompatibility.isShaderActive()) shaderState.enter();
            else shaderState.enterCleanup();
            if (shaders.enter()) {
                // The first pipeline construction compiles shaders; sample the actual capture time after it.
                camera.setup(mc.level, mc.player, false, false, tick);
                if (!WaCameraClient.recordingFrameReady()) return;
            }
            target.bindWrite(true);
            var pose = WaCameraClient.currentPose();
            Matrix4f view = new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf()));
            float far = Math.max(64, mc.options.getEffectiveRenderDistance() * 64);
            Matrix4f projection = new Matrix4f().setPerspective((float) Math.toRadians(pose.fov()),
                    target.width / (float) target.height, .05f, far);
            renderer.resetProjectionMatrix(projection);
            mc.levelRenderer.prepareCullFrustum(camera.getPosition(), view, projection);
            mc.levelRenderer.renderLevel(delta, false, camera, renderer, renderer.lightTexture(), view, projection);
            shaders.finish();
            WaCameraClient.captureFrame();
            ++renderedFrames;
        } finally {
            try {
                try {
                    try { shaders.exit(); }
                    finally { shaderState.exit(); }
                } finally {
                    try { if (rendererStateSaved) levels.wacamera$endCapture(); }
                    finally { SodiumCompatibility.endCapture(); }
                }
            } finally {
                WaCameraClient.setRecordingPass(false);
                waterTarget = FogRendererAccessor.wacamera$getTargetBiomeFog();
                waterPrevious = FogRendererAccessor.wacamera$getPreviousBiomeFog();
                waterChanged = FogRendererAccessor.wacamera$getBiomeChangedTime();
                FogRendererAccessor.wacamera$setTargetBiomeFog(targetFog);
                FogRendererAccessor.wacamera$setPreviousBiomeFog(previousFog);
                FogRendererAccessor.wacamera$setBiomeChangedTime(fogTime);
                try {
                    RenderSystem.getModelViewStack().popMatrix();
                    RenderSystem.applyModelViewMatrix();
                } finally {
                    try { normal.restore(-1); }
                    finally { mc.getProfiler().pop(); }
                }
            }
        }
    }

    @Override public void close() {
        RenderState normal = new RenderState();
        int deleting = target.frameBufferId;
        try {
            try {
                shaderState.enterCleanup();
                try { shaders.close(); }
                finally { shaderState.exit(); }
            } finally { target.destroyBuffers(); }
        }
        finally {
            normal.restore(deleting);
        }
    }

    /** Restore both GL state and Minecraft's state cache after drawing or destroying an Iris pipeline. */
    private static final class RenderState {
        private final int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        private final int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        private final int[] viewport = new int[4], scissorBox = new int[4];
        private final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        private final byte[] colorMask = new byte[4];
        private final int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        private final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        private final int dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        private final int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        private final int dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        private final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        private final Matrix4f textureMatrix = new Matrix4f(RenderSystem.getTextureMatrix());
        private final net.minecraft.client.renderer.ShaderInstance shader = RenderSystem.getShader();
        private final float[] shaderColor = RenderSystem.getShaderColor().clone();
        private final int[] shaderTextures = new int[12];
        private final VertexSorting sorting = RenderSystem.getVertexSorting();

        private RenderState() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
            for (int i = 0; i < shaderTextures.length; i++) shaderTextures[i] = RenderSystem.getShaderTexture(i);
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var colors = stack.malloc(4);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colors);
                colors.get(colorMask);
            }
        }

        private void restore(int deleting) {
            try {
                RenderSystem.setProjectionMatrix(projection, sorting);
                RenderSystem.setTextureMatrix(textureMatrix);
                RenderSystem.setShader(() -> shader);
                RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
                for (int i = 0; i < shaderTextures.length; i++) RenderSystem.setShaderTexture(i, shaderTextures[i]);
                if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
                if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
                if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
                RenderSystem.depthFunc(depthFunc);
                RenderSystem.depthMask(depthMask);
                RenderSystem.colorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0);
                RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            } finally {
                int main = Minecraft.getInstance().getMainRenderTarget().frameBufferId;
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read == deleting ? main : read);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw == deleting ? main : draw);
                RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
                if (scissor) RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
                else RenderSystem.disableScissor();
            }
        }
    }
}
