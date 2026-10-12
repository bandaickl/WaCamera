package dev.wacamera.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.wacamera.core.CameraMarkerGeometry;
import dev.wacamera.core.Vec3d;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** World-space camera indicator drawn only into the player's completed world pass. */
public final class CameraMarkerRenderer {
    private static long renderedMarkers;
    private static long renderedCaptureMarkers;

    private CameraMarkerRenderer() { }

    /** Cumulative successful draws, used by the opt-in game smoke tests. */
    public static long renderedMarkers() { return renderedMarkers; }
    /** Must remain zero: recording frames must never contain this editing aid. */
    public static long renderedCaptureMarkers() { return renderedCaptureMarkers; }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || WaCameraClient.isRecordingPass()
                || WaCameraClient.state() == WaCameraClient.State.PREVIEW) return;
        var pose = WaCameraClient.markerPose(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        if (pose == null) return;
        var segments = CameraMarkerGeometry.segments(pose, WaCameraClient.recordingAspectRatio());
        if (segments.isEmpty()) return;

        var shader = RenderSystem.getShader();
        float[] color = RenderSystem.getShaderColor().clone();
        float lineWidth = RenderSystem.getShaderLineWidth();
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), destinationRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), destinationAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB), equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING), vertexBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int elementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();

        RenderSystem.getModelViewStack().pushMatrix();
        try (ByteBufferBuilder memory = new ByteBufferBuilder(8192)) {
            // Do not use RenderType.lines(): its Fabulous output state targets the entity FBO.
            // At AFTER_LEVEL the display target already contains the finished world composite.
            RenderSystem.getModelViewStack().set(event.getModelViewMatrix());
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), sorting);
            RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.lineWidth(2);
            // This editing aid remains visible through terrain so the user can always locate the camera.
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.blendEquation(GL14.GL_FUNC_ADD);

            BufferBuilder buffer = new BufferBuilder(memory, VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
            Vec3 eye = event.getCamera().getPosition();
            for (var segment : segments) {
                Vec3d from = segment.from(), to = segment.to();
                double dx = to.x() - from.x(), dy = to.y() - from.y(), dz = to.z() - from.z();
                double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (!(length > 0)) continue;
                float nx = (float)(dx / length), ny = (float)(dy / length), nz = (float)(dz / length);
                int red = segment.direction() ? 255 : 55;
                int green = segment.direction() ? 192 : 225;
                int blue = segment.direction() ? 64 : 255;
                vertex(buffer, from, eye, red, green, blue, nx, ny, nz);
                vertex(buffer, to, eye, red, green, blue, nx, ny, nz);
            }
            BufferUploader.drawWithShader(buffer.buildOrThrow());
            renderedMarkers++;
            if (WaCameraClient.isRecordingPass()) renderedCaptureMarkers++;
        } finally {
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            RenderSystem.lineWidth(lineWidth);
            RenderSystem.depthMask(depthMask);
            RenderSystem.depthFunc(depthFunction);
            if (depthTest) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            RenderSystem.blendFuncSeparate(sourceRgb, destinationRgb, sourceAlpha, destinationAlpha);
            GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            BufferUploader.invalidate();
            RenderSystem.glBindVertexArray(vertexArray);
            RenderSystem.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
            if (vertexArray != 0) RenderSystem.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
            GlStateManager._glUseProgram(program);
        }
    }

    private static void vertex(BufferBuilder buffer, Vec3d point, Vec3 eye, int red, int green, int blue,
                               float nx, float ny, float nz) {
        // Subtract in double precision before converting to the GPU's camera-relative floats.
        buffer.addVertex((float)(point.x() - eye.x), (float)(point.y() - eye.y), (float)(point.z() - eye.z))
                .setColor(red, green, blue, 240).setNormal(nx, ny, nz);
    }
}
