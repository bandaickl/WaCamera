#pragma once
#include <algorithm>
#include <cstdint>
#include <stdexcept>

namespace wacamera {
constexpr int64_t nanosPerSecond = 1000000000LL;
struct PacketPlacement { uint64_t silenceFrames; uint64_t skipFrames; };

inline void validatePacketTime(int64_t packetTime, int64_t started, int64_t now) {
    // Silence may suppress packets for arbitrarily long periods. Only reject
    // timestamps outside the actual capture interval, not a long silent gap.
    if (packetTime > now + nanosPerSecond || packetTime < started - nanosPerSecond)
        throw std::runtime_error("Audio packet timestamp is outside the capture interval");
}

inline PacketPlacement placePacket(int64_t packetTime, int64_t firstSample,
                                  uint64_t writtenFrames, uint32_t packetFrames, uint32_t rate) {
    const int64_t desiredFrame = (packetTime - firstSample) * rate / nanosPerSecond;
    const int64_t delta = desiredFrame - static_cast<int64_t>(writtenFrames);
    const int64_t tolerance = rate / 500; // Ignore timestamp jitter below 2 ms.
    if (delta > tolerance) return {static_cast<uint64_t>(delta), 0};
    if (delta < -tolerance) return {0, std::min(static_cast<uint64_t>(-delta), static_cast<uint64_t>(packetFrames))};
    return {0, 0};
}
}
