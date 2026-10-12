package dev.wacamera.client;

import dev.wacamera.recorder.FfmpegRecorder;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** Three GPU slots, one reusable CPU image. A slow encoder never grows this ring. */
public final class GlFrameCapture implements AutoCloseable {
    private final int width, height, bytes;
    private int fbo, texture;
    private final int[] buffers = new int[3];
    private final long[] fences = new long[3];
    private final long[] timestamps = new long[3];
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private ByteBuffer cpu;
    private long lastIssued;

    public GlFrameCapture(int width, int height) {
        this.width = width;
        this.height = height;
        bytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        int oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int oldBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            cpu = MemoryUtil.memAlloc(bytes);
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Recording framebuffer is incomplete");
            for (int i = 0; i < buffers.length; i++) {
                buffers[i] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[i]);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, bytes, GL15.GL_STREAM_READ);
            }
        } catch (RuntimeException e) { close(); throw e; }
        finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldBuffer);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDraw);
        }
    }

    public void capture(int source, int sourceWidth, int sourceHeight, long nanos, FfmpegRecorder recorder, boolean first) {
        int oldPack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            poll(recorder, false);
            // No benefit in pulling many more source frames than a 60 fps export needs.
            if (!first && nanos - lastIssued < 8_000_000L) return;
            int slot = -1;
            for (int i = 0; i < fences.length; i++) if (fences[i] == 0) { slot = i; break; }
            if (slot < 0) return;
            int oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
            int rowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
            int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
            int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            float[] clear = new float[4];
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clear);
            try {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
                GL11.glClearColor(0, 0, 0, 1);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                double scale = Math.min(width / (double)sourceWidth, height / (double)sourceHeight);
                int w = (int)Math.round(sourceWidth * scale), h = (int)Math.round(sourceHeight * scale);
                int x = (width - w) / 2, y = (height - h) / 2;
                GL30.glBlitFramebuffer(0, 0, sourceWidth, sourceHeight, x, y, x + w, y + h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
                GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
                GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
                if (first) {
                    // Bootstrap T0 from the first actual camera frame, before asynchronous slots.
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                    cpu.clear();
                    GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, cpu);
                    flipRowsInPlace();
                    recorder.submitFrame(cpu, nanos);
                } else {
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[slot]);
                    GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
                    timestamps[slot] = nanos;
                    fences[slot] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                    pending.addLast(slot);
                }
                lastIssued = nanos;
            } finally {
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
                GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, rowLength);
                GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
                GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
                GL11.glClearColor(clear[0], clear[1], clear[2], clear[3]);
                if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDraw);
            }
        } finally { GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldPack); }
    }

    private void poll(FfmpegRecorder recorder, boolean drain) {
        while (!pending.isEmpty()) {
            int slot = pending.peekFirst();
            int result = GL32.glClientWaitSync(fences[slot], drain ? GL32.GL_SYNC_FLUSH_COMMANDS_BIT : 0, drain ? 1_000_000 : 0);
            if (result == GL32.GL_TIMEOUT_EXPIRED) break;
            if (result == GL32.GL_WAIT_FAILED) throw new IllegalStateException("GPU recording transfer failed");
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[slot]);
            ByteBuffer mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, bytes, GL30.GL_MAP_READ_BIT);
            if (mapped == null) throw new IllegalStateException("GPU recording buffer could not be mapped");
            try {
                cpu.clear();
                int rowBytes = width * 4;
                for (int row = height - 1; row >= 0; row--) {
                    ByteBuffer line = mapped.duplicate();
                    line.position(row * rowBytes).limit((row + 1) * rowBytes);
                    cpu.put(line);
                }
                cpu.flip();
                recorder.submitFrame(cpu, timestamps[slot]);
            } finally {
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                GL32.glDeleteSync(fences[slot]);
                fences[slot] = 0;
                pending.removeFirst();
            }
        }
    }

    private void flipRowsInPlace() {
        int stride = width * 4;
        for (int y = 0; y < height / 2; y++) {
            int a = y * stride, b = (height - y - 1) * stride;
            for (int x = 0; x < stride; x += 4) {
                int top = cpu.getInt(a + x);
                cpu.putInt(a + x, cpu.getInt(b + x));
                cpu.putInt(b + x, top);
            }
        }
        cpu.position(0).limit(bytes);
    }

    /** Poll on every display frame, independently of the slower timelapse capture cadence. Never waits for the GPU. */
    public void pollReady(FfmpegRecorder recorder) {
        int oldPack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try { poll(recorder, false); }
        finally { GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldPack); }
    }

    public void drain(FfmpegRecorder recorder) {
        int oldPack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try { poll(recorder, true); }
        finally { GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, oldPack); }
    }

    @Override public void close() {
        for (int i = 0; i < buffers.length; i++) {
            if (fences[i] != 0) GL32.glDeleteSync(fences[i]);
            if (buffers[i] != 0) GL15.glDeleteBuffers(buffers[i]);
            fences[i] = 0; buffers[i] = 0;
        }
        pending.clear();
        if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
        if (texture != 0) GL11.glDeleteTextures(texture);
        fbo = 0; texture = 0;
        if (cpu != null) MemoryUtil.memFree(cpu);
        cpu = null;
    }
}
