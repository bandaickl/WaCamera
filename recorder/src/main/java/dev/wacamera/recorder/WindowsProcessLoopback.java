package dev.wacamera.recorder;

import com.sun.jna.CallbackReference;
import com.sun.jna.CallbackThreadInitializer;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Windows process-loopback capture using Minecraft's existing JNA runtime and Windows system DLLs.
 * All instance methods must run on the platform thread that calls open(). No executable is deployed.
 *
 * ABI references: Windows SDK audioclient.h, audioclientactivationparams.h, mmdeviceapi.h,
 * propidlbase.h and byte-packed mmreg.h (checked against SDK 10.0.26100.0).
 * https://learn.microsoft.com/en-us/windows/win32/api/mmdeviceapi/nf-mmdeviceapi-activateaudiointerfaceasync
 * https://learn.microsoft.com/en-us/windows/win32/api/combaseapi/nf-combaseapi-cocreatefreethreadedmarshaler
 */
final class WindowsProcessLoopback implements AutoCloseable {
    private static final int RATE = 48_000;
    private static final int BLOCK_ALIGN = 4;
    private static final int MAX_PACKET_FRAMES = RATE * 10;
    private static final int ACTIVATION_TIMEOUT_SECONDS = 15;
    private static final int S_OK = 0;
    private static final int E_NOINTERFACE = 0x80004002;
    private static final int E_POINTER = 0x80004003;
    private static final int E_FAIL = 0x80004005;
    private static final int WAIT_TIMEOUT = 258;
    private static final int AUDCLNT_S_BUFFER_EMPTY = 0x08890001;

    // A failed Windows activation has no cancellation API. Bound retained, unfinished requests.
    private static final Semaphore ACTIVATION_SLOTS = new Semaphore(4);
    private static final ConcurrentHashMap<Long, Activation> ACTIVATIONS = new ConcurrentHashMap<>();
    private static volatile long performanceFrequency;

    record Packet(byte[] pcm, int frames, long qpcNanos) { }

    private final Thread owner = Thread.currentThread();
    private final IntByReference available = new IntByReference();
    private final IntByReference frames = new IntByReference();
    private final IntByReference flags = new IntByReference();
    private final LongByReference packetQpc = new LongByReference();
    private final PointerByReference packetData = new PointerByReference();
    private Pointer client;
    private Pointer capture;
    private Pointer samples;
    private boolean comInitialized;
    private boolean started;
    private boolean closed;

    private WindowsProcessLoopback() { }

    static WindowsProcessLoopback open() throws IOException {
        requireWindowsX64();
        WindowsProcessLoopback result = new WindowsProcessLoopback();
        boolean success = false;
        try {
            // S_FALSE also represents successful initialization and needs CoUninitialize.
            check(Api.OLE.CoInitializeEx(null, 0), "Initialize audio COM apartment");
            result.comInitialized = true;
            result.client = Activation.activate();
            result.samples = Api.KERNEL.CreateEventW(null, 0, 0, null);
            if (isNull(result.samples)) throw win32Error("Create audio event");

            // WAVEFORMATEX has pack(1): 18 bytes, including cbSize at offset 16.
            try (Memory format = new Memory(18)) {
                format.clear();
                format.setShort(0, (short) 1); // WAVE_FORMAT_PCM
                format.setShort(2, (short) 2);
                format.setInt(4, RATE);
                format.setInt(8, RATE * BLOCK_ALIGN);
                format.setShort(12, (short) BLOCK_ALIGN);
                format.setShort(14, (short) 16);
                // IAudioClient slots: Initialize=3, Start=10, Stop=11, SetEventHandle=13, GetService=14.
                check(call(result.client, 3, 0, 0x80060000, 0L, 0L, format, null),
                        "Initialize process audio (PCM16 stereo 48000 Hz)");
            }
            // LOOPBACK | EVENTCALLBACK | AUTOCONVERTPCM; never fall back to an endpoint-wide mix.
            check(call(result.client, 13, result.samples), "Set audio event");
            PointerByReference service = new PointerByReference();
            int hr = call(result.client, 14, Api.IID_CAPTURE, service);
            result.capture = service.getValue();
            check(hr, "Get audio capture service");
            if (isNull(result.capture)) throw new IOException("Windows returned no audio capture service");
            success = true;
            return result;
        } catch (RuntimeException | LinkageError e) {
            throw new IOException("Could not initialize Windows process audio", e);
        } finally {
            if (!success) result.close();
        }
    }

    void start() throws IOException {
        requireOpen();
        if (!started) {
            check(call(client, 10), "Start process audio");
            started = true;
        }
    }

    void stop() throws IOException {
        requireOpen();
        if (started) {
            check(call(client, 11), "Stop process audio");
            started = false;
        }
    }

    void awaitSamples(int timeoutMillis) throws IOException {
        requireOpen();
        if (timeoutMillis < 0 || timeoutMillis > 60_000) {
            throw new IOException("Audio wait must be between 0 and 60000 milliseconds");
        }
        if (Thread.currentThread().isInterrupted()) throw new IOException("Audio wait interrupted");
        int wait = Api.KERNEL.WaitForSingleObject(samples, timeoutMillis);
        if (wait != 0 && wait != WAIT_TIMEOUT) throw win32Error("Wait for audio samples");
    }

    Packet readPacket() throws IOException {
        requireOpen();
        check(call(capture, 5, available), "Read next audio packet size");
        if (available.getValue() == 0) return null;
        int hr = call(capture, 3, packetData, frames, flags, null, packetQpc);
        check(hr, "Read audio packet");
        if (hr == AUDCLNT_S_BUFFER_EMPTY || frames.getValue() == 0) return null;
        int count = frames.getValue();
        Packet packet;
        try {
            if (count < 0 || count > MAX_PACKET_FRAMES) throw new IOException("Invalid Windows audio packet size");
            byte[] pcm = null;
            if ((flags.getValue() & 0x2) == 0) { // AUDCLNT_BUFFERFLAGS_SILENT permits a null data pointer.
                Pointer data = packetData.getValue();
                if (isNull(data)) throw new IOException("Windows returned an empty audio data pointer");
                pcm = data.getByteArray(0, count * BLOCK_ALIGN);
            }
            long timestamp = packetQpc.getValue();
            if ((flags.getValue() & 0x4) != 0 || timestamp == 0) { // TIMESTAMP_ERROR
                timestamp = 0;
            } else {
                if (timestamp < 0 || timestamp > Long.MAX_VALUE / 100) {
                    throw new IOException("Invalid Windows audio timestamp");
                }
                timestamp *= 100; // GetBuffer returns QPC already converted to 100-nanosecond units.
            }
            packet = new Packet(pcm, count, timestamp);
        } catch (IOException | RuntimeException | Error e) {
            try { check(call(capture, 4, count), "Release audio packet"); }
            catch (IOException | RuntimeException | LinkageError releaseError) { e.addSuppressed(releaseError); }
            throw e;
        }
        // Copy before releasing, always on the same worker. No native buffer escapes this method.
        check(call(capture, 4, count), "Release audio packet");
        return packet;
    }

    static long qpcNanos() throws IOException {
        requireWindowsX64();
        try {
            long frequency = performanceFrequency;
            if (frequency == 0) {
                LongByReference out = new LongByReference();
                if (Api.KERNEL.QueryPerformanceFrequency(out) == 0 || out.getValue() <= 0) {
                    throw win32Error("Read audio clock frequency");
                }
                frequency = out.getValue();
                performanceFrequency = frequency;
            }
            LongByReference counter = new LongByReference();
            if (Api.KERNEL.QueryPerformanceCounter(counter) == 0) throw win32Error("Read audio clock");
            long ticks = counter.getValue();
            if (ticks < 0) throw new IOException("Invalid Windows performance counter");
            // Split before multiplication. The fractional term stays below one second.
            return Math.addExact(Math.multiplyExact(ticks / frequency, 1_000_000_000L),
                    (long) ((ticks % frequency) * (1_000_000_000.0 / frequency)));
        } catch (RuntimeException | LinkageError e) {
            throw new IOException("Could not read Windows audio clock", e);
        }
    }

    @Override public void close() {
        if (closed) return;
        if (Thread.currentThread() != owner) throw new IllegalStateException("Audio must close on its owning worker");
        closed = true;
        try {
            if (started && !isNull(client)) {
                try { call(client, 11); } catch (RuntimeException | LinkageError ignored) { }
            }
        } finally {
            started = false;
            releaseQuietly(capture);
            capture = null;
            releaseQuietly(client);
            client = null;
            try {
                // The audio engine may use the event until both COM interfaces are released.
                if (!isNull(samples)) Api.KERNEL.CloseHandle(samples);
            } finally {
                samples = null;
                if (comInitialized) {
                    comInitialized = false;
                    Api.OLE.CoUninitialize();
                }
            }
        }
    }

    private void requireOpen() throws IOException {
        if (Thread.currentThread() != owner) throw new IOException("Audio accessed outside its owning worker");
        if (closed || isNull(capture)) throw new IOException("Audio capture is closed");
    }

    private static void requireWindowsX64() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.startsWith("windows") || !(arch.equals("amd64") || arch.equals("x86_64"))) {
            throw new IOException("Process audio recording requires Windows x64");
        }
        if (Native.POINTER_SIZE != 8) throw new IOException("Process audio recording requires a 64-bit JVM");
    }

    private static int call(Pointer object, int slot, Object... arguments) {
        Object[] nativeArguments = new Object[arguments.length + 1];
        nativeArguments[0] = object;
        System.arraycopy(arguments, 0, nativeArguments, 1, arguments.length);
        Pointer function = object.getPointer(0).getPointer((long) slot * Native.POINTER_SIZE);
        return Function.getFunction(function, Function.ALT_CONVENTION).invokeInt(nativeArguments);
    }

    private static void check(int hr, String operation) throws IOException {
        if (hr < 0) throw new IOException(operation + " failed (HRESULT 0x" + String.format(Locale.ROOT, "%08X", hr) + ")");
    }

    private static IOException win32Error(String operation) {
        return new IOException(operation + " failed (Windows error " + Api.KERNEL.GetLastError() + ")");
    }

    private static boolean isNull(Pointer pointer) { return pointer == null || Pointer.nativeValue(pointer) == 0; }

    private static void releaseQuietly(Pointer pointer) {
        if (!isNull(pointer)) {
            try { call(pointer, 2); } catch (RuntimeException | LinkageError ignored) { }
        }
    }

    private static Memory guid(String text) {
        UUID value = UUID.fromString(text);
        Memory memory = new Memory(16);
        long most = value.getMostSignificantBits();
        memory.setInt(0, (int) (most >>> 32));
        memory.setShort(4, (short) (most >>> 16));
        memory.setShort(6, (short) most);
        long least = value.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) memory.setByte(8L + i, (byte) (least >>> (56 - i * 8)));
        return memory;
    }

    /** Lazy bindings: merely loading the recorder never loads a Windows DLL. */
    private static final class Api {
        static final Kernel KERNEL = Native.load("kernel32", Kernel.class);
        static final Ole OLE = Native.load(systemDll("ole32.dll"), Ole.class);
        static final Mmdevapi AUDIO = Native.load(systemDll("mmdevapi.dll"), Mmdevapi.class);
        static final Memory IID_UNKNOWN = guid("00000000-0000-0000-C000-000000000046");
        static final Memory IID_MARSHAL = guid("00000003-0000-0000-C000-000000000046");
        static final Memory IID_AGILE = guid("94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90");
        static final Memory IID_COMPLETED = guid("41D949AB-9862-444A-80F6-C261334DA5EB");
        static final Memory IID_CLIENT = guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2");
        static final Memory IID_CAPTURE = guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317");

        private static String systemDll(String name) {
            char[] directory = new char[32768];
            int length = KERNEL.GetSystemDirectoryW(directory, directory.length);
            if (length <= 0 || length >= directory.length) throw new IllegalStateException("Cannot locate Windows system libraries");
            return new String(directory, 0, length) + "\\" + name;
        }
    }

    public interface Kernel extends StdCallLibrary {
        int GetCurrentProcessId();
        int GetLastError();
        int GetSystemDirectoryW(char[] buffer, int size);
        int QueryPerformanceCounter(LongByReference value);
        int QueryPerformanceFrequency(LongByReference value);
        Pointer CreateEventW(Pointer attributes, int manualReset, int initialState, WString name);
        int WaitForSingleObject(Pointer handle, int milliseconds);
        int CloseHandle(Pointer handle);
    }

    public interface Ole extends StdCallLibrary {
        int CoInitializeEx(Pointer reserved, int apartment);
        void CoUninitialize();
        int CoCreateFreeThreadedMarshaler(Pointer outer, PointerByReference marshaler);
    }

    public interface Mmdevapi extends StdCallLibrary {
        int ActivateAudioInterfaceAsync(WString device, Pointer iid, Pointer parameters,
                                        Pointer completionHandler, PointerByReference operation);
    }

    public interface QueryCallback extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer self, Pointer iid, Pointer result);
    }

    public interface ReferenceCallback extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer self);
    }

    public interface CompletionCallback extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer self, Pointer operation);
    }

    /**
     * Four permanent trampolines shared by every request. In particular, the final Release may free
     * an instance, but can never free the libffi trampoline that is currently returning to Windows.
     */
    private static final class Callbacks {
        static final QueryCallback QUERY = (self, iid, result) -> {
            try {
                Activation activation = ACTIVATIONS.get(Pointer.nativeValue(self));
                if (activation == null) return E_POINTER;
                return activation.query(iid, result);
            } catch (Throwable ignored) { return E_FAIL; }
        };
        static final ReferenceCallback ADD = self -> {
            try {
                Activation activation = ACTIVATIONS.get(Pointer.nativeValue(self));
                return activation == null ? 0 : activation.addReference();
            } catch (Throwable ignored) { return 0; }
        };
        static final ReferenceCallback RELEASE = self -> {
            try {
                Activation activation = ACTIVATIONS.get(Pointer.nativeValue(self));
                return activation == null ? 0 : activation.releaseReference();
            } catch (Throwable ignored) { return 0; }
        };
        static final CompletionCallback COMPLETE = (self, operation) -> {
            try {
                Activation activation = ACTIVATIONS.get(Pointer.nativeValue(self));
                return activation == null ? E_POINTER : activation.complete(operation);
            } catch (Throwable ignored) { return E_FAIL; }
        };
        static final Memory VTABLE = createVtable();

        private static Memory createVtable() {
            var initializer = new CallbackThreadInitializer(true, true, "WaCamera audio COM callback");
            Native.setCallbackThreadInitializer(QUERY, initializer);
            Native.setCallbackThreadInitializer(ADD, initializer);
            Native.setCallbackThreadInitializer(RELEASE, initializer);
            Native.setCallbackThreadInitializer(COMPLETE, initializer);
            Memory table = new Memory(4L * Native.POINTER_SIZE);
            table.setPointer(0, CallbackReference.getFunctionPointer(QUERY));
            table.setPointer(8, CallbackReference.getFunctionPointer(ADD));
            table.setPointer(16, CallbackReference.getFunctionPointer(RELEASE));
            table.setPointer(24, CallbackReference.getFunctionPointer(COMPLETE));
            return table;
        }
    }

    /** A Java strong-reference root supplements COM reference counts until Windows is finished. */
    private static final class Activation {
        private final Memory object = new Memory(8);
        private final Memory parameters = new Memory(12);
        private final Memory variant = new Memory(24);
        private final CountDownLatch done = new CountDownLatch(1);
        private final long address = Pointer.nativeValue(object);
        private int references = 1; // Caller-owned IUnknown reference.
        private volatile Pointer marshaler;
        private Pointer activatedClient;
        private IOException failure;
        private boolean completed;
        private boolean abandoned;

        private Activation() {
            object.setPointer(0, Callbacks.VTABLE);
            // AUDIOCLIENT_ACTIVATION_PARAMS: enum at 0, DWORD PID at 4, loopback enum at 8.
            parameters.setInt(0, 1); // AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK
            parameters.setInt(4, Api.KERNEL.GetCurrentProcessId());
            parameters.setInt(8, 0); // INCLUDE_TARGET_PROCESS_TREE only.
            // x64 PROPVARIANT/VT_BLOB: header=8, ULONG size at 8, aligned pointer at 16.
            variant.clear();
            variant.setShort(0, (short) 65); // VT_BLOB
            variant.setInt(8, 12);
            variant.setPointer(16, parameters);
        }

        static Pointer activate() throws IOException {
            if (!ACTIVATION_SLOTS.tryAcquire()) {
                throw new IOException("Previous Windows audio activations have not finished; restart Minecraft before retrying");
            }
            Activation activation;
            try { activation = new Activation(); }
            catch (RuntimeException | Error e) { ACTIVATION_SLOTS.release(); throw e; }
            ACTIVATIONS.put(activation.address, activation);
            PointerByReference operation = new PointerByReference();
            try {
                PointerByReference marshaler = new PointerByReference();
                int hr = Api.OLE.CoCreateFreeThreadedMarshaler(activation.object, marshaler);
                activation.marshaler = marshaler.getValue();
                check(hr, "Create agile audio completion handler");
                if (isNull(activation.marshaler)) throw new IOException("Windows returned no audio marshaler");
                check(Api.AUDIO.ActivateAudioInterfaceAsync(new WString("VAD\\Process_Loopback"),
                                Api.IID_CLIENT, activation.variant, activation.object, operation),
                        "Activate process audio (Windows build 20348 or newer required)");
                if (isNull(operation.getValue())) throw new IOException("Windows returned no audio activation operation");
                try {
                    if (!activation.done.await(ACTIVATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IOException("Windows process audio activation timed out after 15 seconds");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Windows process audio activation interrupted", e);
                }
                synchronized (activation) {
                    if (activation.failure != null) throw activation.failure;
                    if (isNull(activation.activatedClient)) throw new IOException("Windows returned no process audio client");
                    Pointer result = activation.activatedClient;
                    activation.activatedClient = null; // Transfer the QI-owned reference to the worker.
                    return result;
                }
            } finally {
                activation.abandon();
                // Windows retains the callback until activation completes AND this reference is released.
                // On timeout the registry keeps the request and its blob alive for a late callback.
                releaseQuietly(operation.getValue());
                activation.releaseReference();
            }
        }

        private int query(Pointer iid, Pointer out) {
            if (isNull(out)) return E_POINTER;
            out.setPointer(0, null);
            if (isNull(iid)) return E_POINTER;
            byte[] requested = iid.getByteArray(0, 16);
            synchronized (this) {
                if (references == 0) return E_NOINTERFACE;
                if (matches(requested, Api.IID_UNKNOWN) || matches(requested, Api.IID_COMPLETED)
                        || matches(requested, Api.IID_AGILE)) {
                    references++;
                    out.setPointer(0, object);
                    return S_OK;
                }
            }
            // Delegate IMarshal to the actual FTM, rather than only claiming to be agile.
            if (matches(requested, Api.IID_MARSHAL) && !isNull(marshaler)) {
                return call(marshaler, 0, iid, out);
            }
            return E_NOINTERFACE;
        }

        private static boolean matches(byte[] requested, Memory known) {
            return Arrays.equals(requested, known.getByteArray(0, 16));
        }

        private synchronized int addReference() {
            return references == 0 ? 0 : ++references;
        }

        private int releaseReference() {
            int remaining;
            synchronized (this) {
                if (references == 0) return 0;
                remaining = --references;
            }
            if (remaining == 0) {
                try {
                    abandon();
                    releaseQuietly(marshaler);
                    marshaler = null;
                } finally {
                    ACTIVATIONS.remove(address, this);
                    variant.close();
                    parameters.close();
                    object.close();
                    ACTIVATION_SLOTS.release();
                }
            }
            return remaining;
        }

        private void abandon() {
            Pointer unused;
            synchronized (this) {
                abandoned = true;
                unused = activatedClient;
                activatedClient = null;
            }
            releaseQuietly(unused);
        }

        private int complete(Pointer operation) {
            if (addReference() == 0) return E_POINTER;
            Pointer result = null;
            IOException problem = null;
            try {
                IntByReference activationResult = new IntByReference(E_FAIL);
                PointerByReference unknown = new PointerByReference();
                try {
                    check(call(operation, 3, activationResult, unknown), "Get process audio activation result");
                    check(activationResult.getValue(), "Activate process audio");
                    if (isNull(unknown.getValue())) throw new IOException("Windows audio activation returned no interface");
                    PointerByReference queried = new PointerByReference();
                    int hr = call(unknown.getValue(), 0, Api.IID_CLIENT, queried);
                    result = queried.getValue();
                    check(hr, "Query process audio client");
                    if (isNull(result)) throw new IOException("Windows audio client query returned no interface");
                } finally { releaseQuietly(unknown.getValue()); }
            } catch (Throwable e) {
                problem = e instanceof IOException io ? io : new IOException("Windows audio activation callback failed", e);
            }
            try {
                synchronized (this) {
                    if (!completed) {
                        completed = true;
                        failure = problem;
                        if (!abandoned && problem == null) {
                            activatedClient = result;
                            result = null;
                        }
                    }
                }
                releaseQuietly(result);
                done.countDown();
                return S_OK;
            } finally { releaseReference(); }
        }
    }
}
