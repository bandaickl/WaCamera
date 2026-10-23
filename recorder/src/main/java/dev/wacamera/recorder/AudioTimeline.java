package dev.wacamera.recorder;

import java.io.IOException;

/** QPC-based packet placement, independent of WASAPI's sometimes repeated device-position counter. */
final class AudioTimeline {
    private static final long SECOND = 1_000_000_000L;
    private static final long JITTER_FRAMES = PcmWaveWriter.RATE / 500; // 2 ms, as in audio_timeline.h.
    private final PcmWaveWriter wave;
    private final long started;
    private long firstSample;
    private long lastEnd;
    private boolean havePacket;
    private boolean estimated;

    AudioTimeline(PcmWaveWriter wave, long started) { this.wave = wave; this.started = started; }

    void accept(WindowsProcessLoopback.Packet packet, long now) throws IOException {
        int count = packet.frames();
        if (count <= 0) throw new IOException("Audio capture returned an invalid frame count");
        long packetTime = packet.qpcNanos();
        if (packetTime == 0) {
            estimated = true;
            packetTime = now - nanosForFrames(count);
        }
        // Long silent gaps are valid. Only reject times outside the capture's real interval.
        if (packetTime > now + SECOND || packetTime < started - SECOND)
            throw new IOException("Audio packet timestamp is outside the capture interval");
        if (!havePacket) { firstSample = packetTime; havePacket = true; }
        long desired = (packetTime - firstSample) * PcmWaveWriter.RATE / SECOND;
        long delta = desired - wave.frames();
        if (delta > JITTER_FRAMES) wave.append(null, 0, delta);
        int skip = delta < -JITTER_FRAMES ? (int) Math.min(-delta, count) : 0;
        if (skip < count) {
            wave.append(packet.pcm(), skip, count - skip);
            lastEnd = packetTime + nanosForFrames(count);
        }
    }

    void finish(long stopped) throws IOException {
        if (stopped < started) throw new IOException("Audio capture clock moved backwards");
        if (!havePacket) {
            firstSample = started;
            wave.append(null, 0, (stopped - started) * PcmWaveWriter.RATE / SECOND);
            lastEnd = firstSample + nanosForFrames(wave.frames());
            estimated = true;
        } else if (stopped > lastEnd) {
            // Process loopback may stop sending packets after the last audible sound.
            // Preserve prior sample/timestamp drift instead of re-basing those samples.
            long tail = (stopped - lastEnd) * PcmWaveWriter.RATE / SECOND;
            if (tail > JITTER_FRAMES) {
                wave.append(null, 0, tail);
                lastEnd += nanosForFrames(tail);
            }
        }
    }

    long firstSampleNanos() { return firstSample; }
    long lastSampleEndNanos() { return lastEnd; }
    boolean timestampEstimated() { return estimated; }
    private static long nanosForFrames(long frames) { return frames * SECOND / PcmWaveWriter.RATE; }
}
