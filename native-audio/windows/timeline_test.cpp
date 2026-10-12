#include "audio_timeline.h"
#include <cassert>
#include <iostream>

int main() {
    using namespace wacamera;
    constexpr int64_t start = 100 * nanosPerSecond;
    // A packet after 15 seconds of silence must preserve the entire gap.
    validatePacketTime(start + 16 * nanosPerSecond, start, start + 16 * nanosPerSecond);
    auto gap = placePacket(start + 16 * nanosPerSecond, start, 48000, 480, 48000);
    assert(gap.silenceFrames == 15 * 48000 && gap.skipFrames == 0);
    auto jitter = placePacket(start + nanosPerSecond + 1000000, start, 48000, 480, 48000);
    assert(jitter.silenceFrames == 0 && jitter.skipFrames == 0);
    auto overlap = placePacket(start, start, 240, 480, 48000);
    assert(overlap.silenceFrames == 0 && overlap.skipFrames == 240);
    auto duplicate = placePacket(start, start, 48000, 480, 48000);
    assert(duplicate.skipFrames == 480);
    bool futureRejected = false, pastRejected = false;
    try { validatePacketTime(start + 3 * nanosPerSecond, start, start); }
    catch (const std::runtime_error&) { futureRejected = true; }
    try { validatePacketTime(start - 3 * nanosPerSecond, start, start); }
    catch (const std::runtime_error&) { pastRejected = true; }
    assert(futureRejected && pastRejected);
    std::cout << "PASS: six audio timeline checks (no audio playback)\n";
}
