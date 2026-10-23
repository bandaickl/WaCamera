package dev.wacamera.recorder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Bounded-memory PCM16 stereo WAV writer, owned exclusively by the audio worker. */
final class PcmWaveWriter implements AutoCloseable {
    static final int RATE = 48_000;
    static final int CHANNELS = 2;
    static final int ALIGN = CHANNELS * Short.BYTES;
    private static final long MAX_DATA_BYTES = 0xffff_ffffL - 36;
    private static final byte[] SILENCE = new byte[8192];
    private final FileChannel file;
    private long frames;

    PcmWaveWriter(Path path) throws IOException {
        file = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36);
            header.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
            header.putShort((short) 1).putShort((short) CHANNELS);
            header.putInt(RATE).putInt(RATE * ALIGN).putShort((short) ALIGN).putShort((short) 16);
            header.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(0).flip();
            write(header);
        } catch (IOException | RuntimeException e) {
            try { file.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    long frames() { return frames; }

    void append(byte[] pcm, int skippedFrames, long count) throws IOException {
        if (count < 0 || skippedFrames < 0 || count > MAX_DATA_BYTES / ALIGN - frames)
            throw new IOException("Audio WAV size limit reached or invalid packet length");
        if (pcm != null && (long) skippedFrames * ALIGN + count * ALIGN > pcm.length)
            throw new IOException("Audio packet is shorter than its frame count");
        if (pcm != null) {
            write(ByteBuffer.wrap(pcm, Math.multiplyExact(skippedFrames, ALIGN), Math.toIntExact(count * ALIGN)));
        } else {
            long remaining = count * ALIGN;
            while (remaining > 0) {
                int bytes = (int) Math.min(remaining, SILENCE.length);
                write(ByteBuffer.wrap(SILENCE, 0, bytes));
                remaining -= bytes;
            }
        }
        frames += count;
    }

    /** Keep a usable header on completion and interrupted-session checkpoints. */
    void checkpoint() throws IOException {
        long position = file.position();
        try {
            ByteBuffer size = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            file.position(4);
            size.putInt((int) (frames * ALIGN + 36)).flip();
            write(size);
            file.position(40);
            size.clear().putInt((int) (frames * ALIGN)).flip();
            write(size);
            file.force(false);
        } finally { file.position(position); }
    }

    private void write(ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) file.write(bytes);
    }

    @Override public void close() throws IOException { file.close(); }
}
