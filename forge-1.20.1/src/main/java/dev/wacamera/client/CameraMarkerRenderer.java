package dev.wacamera.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.wacamera.core.CameraMarkerGeometry;
import dev.wacamera.core.Vec3d;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** A player-view guide, drawn after world composition and never into a recording target. */
public final class CameraMarkerRenderer {
    // A private builder cannot flush another mod's buffered world geometry.
    private static final BufferBuilder LINES = new BufferBuilder(8192);
    private static long renderedMarkers;
    private static long renderedCaptureMarkers;

    private CameraMarkerRenderer() {}

    /** Number of completed marker draws; useful for isolated game smoke tests. */
    public static long renderedMarkers() { return renderedMarkers; }
    public static long renderedCaptureMarkers() { return renderedCaptureMarkers; }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || WaCameraClient.isRecordingPass()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.options.hideGui) return;
        var pose = WaCameraClient.markerPose(event.getPartialTick());
        if (pose == null) return; // Preview/idle/preparation/finalization do not display markers.
        var segments = CameraMarkerGeometry.segments(pose, WaCameraClient.recordingAspectRatio());
        ShaderInstance lineShader = GameRenderer.getRendertypeLinesShader();
        if (segments.isEmpty() || lineShader == null) return;

        var eye = event.getCamera().getPosition();
        // Forge 1.20.1's AFTER_LEVEL event supplies the projection pose stack, not the
        // world-view pose stack. Match GameRenderer's pitch / (yaw + 180) view rotation.
        // Camera.rotation() has +Z as its local forward in 1.20.1: its conjugate alone
        // leaves a camera at yaw=0 looking down +Z, behind OpenGL's -Z near plane.
        Matrix4f view = new Matrix4f().rotationX((float) Math.toRadians(event.getCamera().getXRot()))
                .rotateY((float) Math.toRadians(event.getCamera().getYRot() + 180.0f));
        Matrix3f normal = new Matrix3f(view);
        try (DrawState state = new DrawState()) {
            // At this stage Fabulous and Oculus have composed their world targets. In Oculus
            // 1.8 the world-shader override is already off; its final color conversion follows.
            mc.getMainRenderTarget().bindWrite(false);
            RenderSystem.viewport(0, 0, mc.getMainRenderTarget().viewWidth, mc.getMainRenderTarget().viewHeight);
            RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.getModelViewStack().pushPose();
            try {
                RenderSystem.getModelViewStack().setIdentity();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.setShader(() -> lineShader);
                RenderSystem.setShaderColor(1, 1, 1, 1);
                RenderSystem.lineWidth(2.0f);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
                RenderSystem.disableCull();
                RenderSystem.disableDepthTest();
                RenderSystem.depthMask(false);
                RenderSystem.colorMask(true, true, true, true);
                RenderSystem.disableScissor();
                LINES.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
                for (var segment : segments) {
                    Vec3d delta = segment.to().subtract(segment.from());
                    double length = delta.length();
                    if (length == 0) continue;
                    float nx = (float) (delta.x() / length), ny = (float) (delta.y() / length), nz = (float) (delta.z() / length);
                    int red = segment.direction() ? 255 : 48;
                    int green = segment.direction() ? 190 : 225;
                    int blue = segment.direction() ? 64 : 255;
                    vertex(segment.from(), eye.x, eye.y, eye.z, view, normal, nx, ny, nz, red, green, blue);
                    vertex(segment.to(), eye.x, eye.y, eye.z, view, normal, nx, ny, nz, red, green, blue);
                }
                BufferUploader.drawWithShader(LINES.end());
                renderedMarkers++;
                if (WaCameraClient.isRecordingPass()) renderedCaptureMarkers++;
            } finally {
                LINES.discard();
                RenderSystem.getModelViewStack().popPose();
                RenderSystem.applyModelViewMatrix();
            }
        }
    }

    private static void vertex(Vec3d point, double eyeX, double eyeY, double eyeZ, Matrix4f view, Matrix3f normal,
                               float nx, float ny, float nz, int red, int green, int blue) {
        // Subtract in double precision before converting to float, including at distant world coordinates.
        LINES.vertex(view, (float) (point.x() - eyeX), (float) (point.y() - eyeY), (float) (point.z() - eyeZ))
                .color(red, green, blue, 235).normal(normal, nx, ny, nz).endVertex();
    }

    /** Restore actual GL state and RenderSystem's mirrored state, including on a failed draw. */
    private static final class DrawState implements AutoCloseable {
        private final ShaderInstance shader = RenderSystem.getShader();
        private final float[] color = RenderSystem.getShaderColor().clone();
        private final float lineWidth = RenderSystem.getShaderLineWidth();
        private final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        private final VertexSorting sorting = RenderSystem.getVertexSorting();
        private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        private final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        private final int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        private final int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        private final int destinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        private final int destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        private final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        private final int equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        private final int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        private final int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        private final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        private final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        private final int[] viewport = new int[4];
        private final int[] scissorBox = new int[4];
        private final int[] colorMask = new int[4];

        private DrawState() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
            GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, colorMask);
        }

        @Override public void close() {
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            RenderSystem.lineWidth(lineWidth);
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.blendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
            GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            RenderSystem.depthMask(depthMask);
            RenderSystem.colorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0);
            RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            if (!scissor) RenderSystem.disableScissor();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            // BufferUploader caches the last bound VAO; invalidate it before restoring a different one.
            BufferUploader.invalidate();
            GlStateManager._glBindVertexArray(vertexArray);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
            GlStateManager._glUseProgram(program);
        }
    }
}
