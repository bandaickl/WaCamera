// WaCamera process-scoped audio capture. No endpoint-wide capture fallback.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <audioclientactivationparams.h>
#include <wrl.h>
#include <atomic>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include <filesystem>
#include "audio_timeline.h"

using Microsoft::WRL::ComPtr;
constexpr uint32_t RATE = 48000;
constexpr uint16_t CHANNELS = 2;
constexpr uint16_t ALIGN = CHANNELS * 2;

class Handle {
    HANDLE value_;
public:
    explicit Handle(HANDLE value = nullptr) : value_(value) {}
    ~Handle() { if (value_ && value_ != INVALID_HANDLE_VALUE) CloseHandle(value_); }
    Handle(const Handle&) = delete;
    Handle& operator=(const Handle&) = delete;
    HANDLE get() const { return value_; }
};

void check(HRESULT hr, const char* operation) {
    if (FAILED(hr)) {
        std::ostringstream message;
        message << operation << " failed (HRESULT 0x" << std::hex << static_cast<uint32_t>(hr) << ')';
        throw std::runtime_error(message.str());
    }
}

int64_t nowNanos() {
    LARGE_INTEGER counter{}, frequency{};
    QueryPerformanceCounter(&counter);
    QueryPerformanceFrequency(&frequency);
    return (counter.QuadPart / frequency.QuadPart) * 1000000000LL
        + (counter.QuadPart % frequency.QuadPart) * 1000000000LL / frequency.QuadPart;
}

std::mutex protocolMutex;
void say(const std::string& text) {
    std::lock_guard<std::mutex> guard(protocolMutex);
    std::cout << text << std::endl;
}

class Activation final : public Microsoft::WRL::RuntimeClass<
    Microsoft::WRL::RuntimeClassFlags<Microsoft::WRL::ClassicCom>,
    IActivateAudioInterfaceCompletionHandler, Microsoft::WRL::FtmBase> {
public:
    Handle completed{CreateEventW(nullptr, TRUE, FALSE, nullptr)};
    HRESULT result = E_PENDING;
    ComPtr<IAudioClient> client;
    STDMETHODIMP ActivateCompleted(IActivateAudioInterfaceAsyncOperation* operation) override {
        HRESULT activationResult = E_FAIL;
        ComPtr<IUnknown> unknown;
        result = operation->GetActivateResult(&activationResult, &unknown);
        if (SUCCEEDED(result)) result = activationResult;
        if (SUCCEEDED(result)) result = unknown.As(&client);
        SetEvent(completed.get());
        return S_OK;
    }
};

class WaveFile {
    Handle file_;
    uint64_t frames_ = 0;
    void write(const void* data, DWORD bytes) {
        DWORD written = 0;
        if (!WriteFile(file_.get(), data, bytes, &written, nullptr) || written != bytes)
            throw std::runtime_error("Writing audio failed; check available disk space");
    }
    void u16(uint16_t value) { write(&value, 2); }
    void u32(uint32_t value) { write(&value, 4); }
public:
    explicit WaveFile(const std::wstring& path) : file_(CreateFileW(path.c_str(), GENERIC_WRITE,
        FILE_SHARE_READ, nullptr, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, nullptr)) {
        if (file_.get() == INVALID_HANDLE_VALUE) throw std::runtime_error("Cannot create audio WAV file");
        write("RIFF", 4); u32(36); write("WAVEfmt ", 8); u32(16); u16(1); u16(CHANNELS);
        u32(RATE); u32(RATE * ALIGN); u16(ALIGN); u16(16); write("data", 4); u32(0);
    }
    uint64_t frames() const { return frames_; }
    void append(const BYTE* data, uint64_t count) {
        if ((frames_ + count) * ALIGN > UINT32_MAX - 36ULL) throw std::runtime_error("WAV size limit reached");
        if (data) write(data, static_cast<DWORD>(count * ALIGN));
        else {
            static const BYTE silence[8192]{};
            uint64_t remaining = count * ALIGN;
            while (remaining) {
                const DWORD bytes = static_cast<DWORD>(remaining > sizeof(silence) ? sizeof(silence) : remaining);
                write(silence, bytes);
                remaining -= bytes;
            }
        }
        frames_ += count;
    }
    void finalize() {
        LARGE_INTEGER offset{};
        offset.QuadPart = 4;
        if (!SetFilePointerEx(file_.get(), offset, nullptr, FILE_BEGIN)) throw std::runtime_error("WAV seek failed");
        u32(static_cast<uint32_t>(frames_ * ALIGN + 36));
        offset.QuadPart = 40;
        if (!SetFilePointerEx(file_.get(), offset, nullptr, FILE_BEGIN)) throw std::runtime_error("WAV seek failed");
        u32(static_cast<uint32_t>(frames_ * ALIGN));
        if (!FlushFileBuffers(file_.get())) throw std::runtime_error("WAV flush failed");
    }
};

struct Options { DWORD pid = 0; std::wstring output, metadata; };
Options parse(int argc, wchar_t** argv) {
    Options options;
    for (int i = 1; i < argc; i += 2) {
        if (i + 1 >= argc) throw std::runtime_error("Missing argument value");
        const std::wstring key = argv[i];
        if (key == L"--pid") {
            size_t consumed = 0;
            const auto value = std::stoull(argv[i + 1], &consumed);
            if (consumed != wcslen(argv[i + 1]) || value == 0 || value > UINT32_MAX)
                throw std::runtime_error("Invalid target PID");
            options.pid = static_cast<DWORD>(value);
        } else if (key == L"--output") options.output = argv[i + 1];
        else if (key == L"--metadata") options.metadata = argv[i + 1];
        else throw std::runtime_error("Unknown argument");
    }
    if (!options.pid || options.output.empty() || options.metadata.empty())
        throw std::runtime_error("Usage: wacamera-audio --pid PID --output audio.wav --metadata audio.json");
    return options;
}

int run(const Options& options) {
    check(CoInitializeEx(nullptr, COINIT_MULTITHREADED), "COM initialization");
    struct ComCleanup { ~ComCleanup() { CoUninitialize(); } } cleanup;
    Handle target(OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, options.pid));
    if (!target.get()) throw std::runtime_error("Target process is not available");

    auto activation = Microsoft::WRL::Make<Activation>();
    if (!activation || !activation->completed.get()) throw std::runtime_error("Cannot create activation callback");
    AUDIOCLIENT_ACTIVATION_PARAMS parameters{};
    parameters.ActivationType = AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK;
    parameters.ProcessLoopbackParams.TargetProcessId = options.pid;
    parameters.ProcessLoopbackParams.ProcessLoopbackMode = PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE;
    PROPVARIANT variant{};
    variant.vt = VT_BLOB;
    variant.blob.cbSize = sizeof(parameters);
    variant.blob.pBlobData = reinterpret_cast<BYTE*>(&parameters);
    ComPtr<IActivateAudioInterfaceAsyncOperation> operation;
    check(ActivateAudioInterfaceAsync(VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK, __uuidof(IAudioClient),
        &variant, activation.Get(), &operation), "Process audio activation (requires Windows build 20348+)");
    if (WaitForSingleObject(activation->completed.get(), 15000) != WAIT_OBJECT_0)
        throw std::runtime_error("Process audio activation timed out");
    check(activation->result, "Process audio activation");
    ComPtr<IAudioClient> client = activation->client;
    WAVEFORMATEX format{};
    format.wFormatTag = WAVE_FORMAT_PCM;
    format.nChannels = CHANNELS;
    format.nSamplesPerSec = RATE;
    format.nAvgBytesPerSec = RATE * ALIGN;
    format.nBlockAlign = ALIGN;
    format.wBitsPerSample = 16;
    check(client->Initialize(AUDCLNT_SHAREMODE_SHARED,
        AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM,
        0, 0, &format, nullptr), "Audio initialization");
    ComPtr<IAudioCaptureClient> capture;
    check(client->GetService(IID_PPV_ARGS(&capture)), "Capture service");
    Handle samples(CreateEventW(nullptr, FALSE, FALSE, nullptr));
    Handle start(CreateEventW(nullptr, TRUE, FALSE, nullptr));
    Handle stop(CreateEventW(nullptr, TRUE, FALSE, nullptr));
    if (!samples.get() || !start.get() || !stop.get()) throw std::runtime_error("Cannot create capture events");
    check(client->SetEventHandle(samples.get()), "Audio event setup");
    WaveFile wave(options.output);
    std::thread commands([&] {
        std::string line;
        while (std::getline(std::cin, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (line.rfind("PING ", 0) == 0) say("PONG " + line.substr(5) + " " + std::to_string(nowNanos()));
            else if (line == "START") SetEvent(start.get());
            else if (line == "STOP") break;
        }
        SetEvent(stop.get());
    });
    // Closing stdin / STOP is the normal termination contract. On an exception,
    // CancelSynchronousIo unblocks the reader so its references never outlive run().
    struct ReaderCleanup {
        std::thread& thread;
        ~ReaderCleanup() {
            if (thread.joinable()) {
                CancelSynchronousIo(thread.native_handle());
                thread.join();
            }
        }
    } readerCleanup{commands};
    say("READY");
    HANDLE waiting[] = {stop.get(), start.get(), target.get()};
    const DWORD waitResult = WaitForMultipleObjects(3, waiting, FALSE, 30000);
    if (waitResult != WAIT_OBJECT_0 + 1) throw std::runtime_error("Audio start cancelled or timed out");
    check(client->Start(), "Audio start");
    const int64_t started = nowNanos();
    say("STARTED " + std::to_string(started));
    int64_t firstSample = 0, lastEnd = 0;
    bool timestampEstimated = false;
    bool havePacket = false;
    auto drain = [&] {
        UINT32 available = 0;
        check(capture->GetNextPacketSize(&available), "Audio packet size");
        while (available) {
            BYTE* data = nullptr;
            UINT32 frames = 0;
            DWORD flags = 0;
            UINT64 position = 0, qpc100ns = 0;
            check(capture->GetBuffer(&data, &frames, &flags, &position, &qpc100ns), "Audio read");
            try {
                int64_t packetTime = static_cast<int64_t>(qpc100ns) * 100;
                if ((flags & AUDCLNT_BUFFERFLAGS_TIMESTAMP_ERROR) || !qpc100ns) {
                    timestampEstimated = true;
                    packetTime = nowNanos() - static_cast<int64_t>(frames) * 1000000000LL / RATE;
                }
                wacamera::validatePacketTime(packetTime, started, nowNanos());
                if (!havePacket) { firstSample = packetTime; havePacket = true; }
                // Process-loopback can report repeated/reset device positions,
                // especially during silence. QPC timestamps, not that device
                // counter, define the recorded timeline. Ignore sub-2ms jitter.
                const auto placement = wacamera::placePacket(packetTime, firstSample, wave.frames(), frames, RATE);
                if (placement.silenceFrames) wave.append(nullptr, placement.silenceFrames);
                const uint64_t skip = placement.skipFrames;
                if (skip < frames) {
                    wave.append((flags & AUDCLNT_BUFFERFLAGS_SILENT) ? nullptr : data + skip * ALIGN, frames - skip);
                    lastEnd = packetTime + static_cast<int64_t>(frames) * 1000000000LL / RATE;
                }
            } catch (...) { capture->ReleaseBuffer(frames); throw; }
            check(capture->ReleaseBuffer(frames), "Audio buffer release");
            check(capture->GetNextPacketSize(&available), "Audio packet size");
        }
    };
    try {
        HANDLE events[] = {stop.get(), samples.get(), target.get()};
        for (;;) {
            const DWORD event = WaitForMultipleObjects(3, events, FALSE, 250);
            if (event == WAIT_OBJECT_0 || event == WAIT_OBJECT_0 + 2) break;
            if (event == WAIT_FAILED) throw std::runtime_error("Audio wait failed");
            drain();
            if (nowNanos() - started > 3610LL * 1000000000LL) throw std::runtime_error("Audio capture time limit reached");
        }
        check(client->Stop(), "Audio stop");
        drain();
    } catch (...) { client->Stop(); try { wave.finalize(); } catch (...) {} throw; }
    const int64_t stopped = nowNanos();
    if (!havePacket) {
        firstSample = started;
        wave.append(nullptr, static_cast<uint64_t>((stopped - started) * RATE / 1000000000LL));
        lastEnd = firstSample + static_cast<int64_t>(wave.frames()) * 1000000000LL / RATE;
        timestampEstimated = true;
    }
    wave.finalize();
    std::ofstream metadata{std::filesystem::path(options.metadata), std::ios::binary | std::ios::trunc};
    metadata << "{\n  \"sampleRate\": " << RATE << ",\n  \"channels\": " << CHANNELS
        << ",\n  \"totalFrames\": " << wave.frames() << ",\n  \"firstSampleNanos\": " << firstSample
        << ",\n  \"lastSampleEndNanos\": " << lastEnd
        << ",\n  \"timestampEstimated\": " << (timestampEstimated ? "true" : "false") << "\n}\n";
    metadata.flush();
    if (!metadata) throw std::runtime_error("Audio metadata write failed");
    say("STOPPED");
    return 0;
}

int wmain(int argc, wchar_t** argv) {
    try {
        if (argc == 2 && std::wstring(argv[1]) == L"--version") {
            std::cout << "wacamera-audio 0.1.0 protocol=1 windows-process-loopback" << std::endl;
            return 0;
        }
        return run(parse(argc, argv));
    } catch (const std::exception& error) {
        say(std::string("ERROR ") + error.what());
        std::cerr << error.what() << std::endl;
        return 1;
    }
}
