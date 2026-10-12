package dev.wacamera.recorder;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs the explicitly requested recording engine. Call from a background task only after
 * the user presses the preparation button. This class neither launches binaries nor changes PATH.
 */
public final class RecordingEngineInstaller {
    public static final String VERSION = "9.0.2";
    public static final String DOWNLOAD_URL =
            "https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-9.0.2-essentials_build.zip";
    // Verified from the build provider's HTTPS checksum endpoint on 2026-09-22.
    // https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-9.0.2-essentials_build.zip.sha256
    public static final String ARCHIVE_SHA256 =
            "60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba";
    static final long MAX_DOWNLOAD_BYTES = 300_000_000L;
    private static final long MAX_EXTRACTED_BYTES = 600_000_000L;
    private static final long MAX_ENTRY_BYTES = 300_000_000L;
    private static final String ARCHIVE_ROOT = "ffmpeg-9.0.2-essentials_build/";
    private static final String INSTALL_NAME = "ffmpeg-9.0.2-gyan-essentials";
    private static final List<String> REQUIRED_FILES = List.of("bin/ffmpeg.exe", "bin/ffprobe.exe",
            "LICENSE.txt", "README.txt", "ORIGIN.txt");
    private static final String MANIFEST = ".installed.properties";

    private RecordingEngineInstaller() {}

    /** Returns the verified ffmpeg.exe path, preserving any existing installation on failure. */
    public static synchronized Path prepareWindows(Path toolsDirectory) throws IOException, InterruptedException {
        requireWindowsX64();
        Objects.requireNonNull(toolsDirectory, "toolsDirectory");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Engine preparation interrupted");
        Path tools = toolsDirectory.toAbsolutePath().normalize();
        Files.createDirectories(tools);
        if (!Files.isDirectory(tools, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Recording tools directory must be a regular directory");
        }
        Path destination = tools.resolve(INSTALL_NAME);
        Path lockPath = tools.resolve(".ffmpeg-prepare.lock");
        try (FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock;
            try { lock = lockChannel.tryLock(); }
            catch (OverlappingFileLockException exception) {
                throw new IOException("Recording engine preparation is already running", exception);
            }
            if (lock == null) throw new IOException("Recording engine preparation is already running");
            try (lock) {
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    verifyInstallation(destination);
                    return destination.resolve("bin/ffmpeg.exe");
                }
                Path staging = Files.createTempDirectory(tools, ".ffmpeg-prepare-");
                Throwable failure = null;
                try {
                    Path archive = staging.resolve("package.zip");
                    download(archive);
                    verifyArchive(archive, ARCHIVE_SHA256);
                    Path install = staging.resolve("install");
                    extractArchive(archive, install);
                    writeOrigin(install);
                    writeManifest(install);
                    verifyInstallation(install);
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Engine preparation interrupted");
                    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Recording engine destination appeared during installation; it was preserved");
                    }
                    try { Files.move(install, destination, StandardCopyOption.ATOMIC_MOVE); }
                    catch (AtomicMoveNotSupportedException exception) {
                        throw new IOException("This filesystem cannot atomically install the recording engine", exception);
                    }
                    return destination.resolve("bin/ffmpeg.exe");
                } catch (IOException | InterruptedException | RuntimeException exception) {
                    failure = exception;
                    throw exception;
                } finally {
                    try { deleteStaging(tools, staging); }
                    catch (IOException cleanup) {
                        if (failure != null) failure.addSuppressed(cleanup);
                        else throw cleanup;
                    }
                }
            }
        }
    }

    private static void requireWindowsX64() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.startsWith("windows") || !(arch.equals("amd64") || arch.equals("x86_64"))) {
            throw new IOException("Automatic engine preparation supports Windows x64 only; select an existing FFmpeg executable on this OS");
        }
    }

    private static void download(Path archive) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(DOWNLOAD_URL))
                .timeout(Duration.ofMinutes(10)).header("User-Agent", "WaCamera/0.1 recording-engine-preparation")
                .GET().build();
        LimitedDownload subscriber = new LimitedDownload(archive, MAX_DOWNLOAD_BYTES);
        CompletableFuture<HttpResponse<Path>> pending = null;
        try {
            pending = client.sendAsync(request, info -> {
                if (info.statusCode() != 200) subscriber.fail(new IOException("Engine download returned HTTP " + info.statusCode()));
                try {
                    if (info.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_DOWNLOAD_BYTES) {
                        subscriber.fail(new IOException("Engine download exceeds 300 MB"));
                    }
                } catch (NumberFormatException exception) {
                    subscriber.fail(new IOException("Invalid download Content-Length", exception));
                }
                return subscriber;
            });
            // Unlike an InputStream response, this deadline includes the complete response body.
            pending.get(10, TimeUnit.MINUTES);
        } catch (TimeoutException exception) {
            throw new IOException("Engine download timed out after 10 minutes", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Engine download failed", cause);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            subscriber.close();
        }
    }

    static void verifyArchive(Path archive, String expectedSha256) throws IOException {
        if (Files.size(archive) > MAX_DOWNLOAD_BYTES) throw new IOException("Engine archive exceeds 300 MB");
        if (!sha256(archive).equals(expectedSha256)) {
            throw new IOException("Recording engine SHA-256 verification failed; nothing was installed");
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65_536];
            for (int read; (read = input.read(buffer)) != -1;) {
                checkInterrupted();
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Extract only the two static executables and the original top-level license/readme. */
    static void extractArchive(Path archive, Path destination) throws IOException {
        Files.createDirectory(destination);
        Set<String> written = new HashSet<>();
        long total = 0;
        int entries = 0;
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                checkInterrupted();
                ZipEntry entry = enumeration.nextElement();
                if (++entries > 10_000) throw new IOException("Recording engine archive has too many entries");
                safeEntryPath(destination, entry.getName());
                if (entry.isDirectory()) continue;
                String normalized = entry.getName().replace('\\', '/');
                if (!normalized.startsWith(ARCHIVE_ROOT)) continue;
                String relative = normalized.substring(ARCHIVE_ROOT.length());
                String outputName = switch (relative.toLowerCase(Locale.ROOT)) {
                    case "bin/ffmpeg.exe" -> "bin/ffmpeg.exe";
                    case "bin/ffprobe.exe" -> "bin/ffprobe.exe";
                    case "license", "license.txt", "license.md" -> "LICENSE.txt";
                    case "readme", "readme.txt", "readme.md" -> "README.txt";
                    default -> null;
                };
                if (outputName == null) continue;
                if (!written.add(outputName)) throw new IOException("Duplicate required archive entry: " + outputName);
                if (entry.getSize() > MAX_ENTRY_BYTES) throw new IOException("Recording engine archive entry is too large");
                Path output = safeEntryPath(destination, outputName);
                Files.createDirectories(output.getParent());
                if (!Files.isDirectory(output.getParent(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Archive extraction parent is not a regular directory");
                }
                long entryBytes = 0;
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[65_536];
                    for (int read; (read = input.read(buffer)) != -1;) {
                        checkInterrupted();
                        entryBytes += read;
                        total += read;
                        if (entryBytes > MAX_ENTRY_BYTES || total > MAX_EXTRACTED_BYTES) {
                            throw new IOException("Expanded recording engine archive exceeds its size limit");
                        }
                        stream.write(buffer, 0, read);
                    }
                }
                if (entryBytes == 0) throw new IOException("Empty required archive entry: " + outputName);
            }
        }
        if (!written.containsAll(List.of("bin/ffmpeg.exe", "bin/ffprobe.exe", "LICENSE.txt", "README.txt"))) {
            throw new IOException("Recording engine archive is missing executables, license or README");
        }
    }

    /** Check every entry, including entries that will not be extracted. */
    static Path safeEntryPath(Path destination, String name) throws IOException {
        String normalized = name.replace('\\', '/');
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.contains(":")) {
            throw new IOException("Unsafe path in recording engine archive");
        }
        for (String component : normalized.split("/")) {
            if (component.equals("..") || component.equals(".") || component.isEmpty()
                    || component.endsWith(".") || component.endsWith(" ")) {
                throw new IOException("Unsafe path in recording engine archive");
            }
        }
        try {
            Path root = destination.toAbsolutePath().normalize();
            Path result = root.resolve(normalized).normalize();
            if (!result.startsWith(root) || result.equals(root)) throw new IOException("Archive entry leaves installation directory");
            return result;
        } catch (InvalidPathException exception) {
            throw new IOException("Invalid path in recording engine archive", exception);
        }
    }

    private static void writeOrigin(Path install) throws IOException {
        String text = """
                WaCamera recording engine - third-party distribution

                Distributor: Gyan Doshi / https://www.gyan.dev/ffmpeg/builds/
                FFmpeg version: %s, Windows x64 static essentials build
                Archive: %s
                Archive SHA-256: %s
                Checksum source: %s.sha256
                FFmpeg's download page links this distributor: https://ffmpeg.org/download.html

                The distributor identifies this build as GPLv3. The original LICENSE and README
                from the verified archive are preserved alongside this notice. WaCamera downloads
                these tools on an explicit preparation request; they are not bundled in its mod JAR.

                Source information published by the distributor for this release:
                https://github.com/FFmpeg/FFmpeg/commit/946fcce07b
                Distribution/build information: https://www.gyan.dev/ffmpeg/builds/
                Build archive and discussion: https://github.com/GyanD/codexffmpeg
                FFmpeg license information: https://ffmpeg.org/legal.html

                Consult the original README and the build provider for corresponding source and
                build instructions, including bundled libraries. The FFmpeg source link alone is
                not a claim to provide every bundled library's corresponding source. Redistribution
                requires preserving and satisfying the applicable licenses.
                """.formatted(VERSION, DOWNLOAD_URL, ARCHIVE_SHA256, DOWNLOAD_URL);
        Files.writeString(install.resolve("ORIGIN.txt"), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static void writeManifest(Path install) throws IOException {
        Properties manifest = new Properties();
        manifest.setProperty("version", VERSION);
        manifest.setProperty("archive.sha256", ARCHIVE_SHA256);
        for (String relative : REQUIRED_FILES) manifest.setProperty(relative + ".sha256", sha256(install.resolve(relative)));
        try (OutputStream output = Files.newOutputStream(install.resolve(MANIFEST), StandardOpenOption.CREATE_NEW)) {
            manifest.store(output, "WaCamera verified installation; local file hashes detect incomplete or changed files");
        }
    }

    private static void verifyInstallation(Path install) throws IOException {
        if (!Files.isDirectory(install, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(install.resolve(MANIFEST), LinkOption.NOFOLLOW_LINKS)
                || Files.size(install.resolve(MANIFEST)) > 16_384) {
            throw new IOException("Existing recording engine folder is incomplete; it was preserved: " + install);
        }
        if (!Files.isDirectory(install.resolve("bin"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Recording engine bin directory is invalid; existing files were preserved");
        }
        Properties manifest = new Properties();
        try (InputStream input = Files.newInputStream(install.resolve(MANIFEST))) { manifest.load(input); }
        if (!VERSION.equals(manifest.getProperty("version")) || !ARCHIVE_SHA256.equals(manifest.getProperty("archive.sha256"))) {
            throw new IOException("Existing recording engine has a different origin; it was preserved");
        }
        for (String relative : REQUIRED_FILES) {
            Path file = install.resolve(relative);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) == 0
                    || Files.size(file) > MAX_ENTRY_BYTES || !sha256(file).equals(manifest.getProperty(relative + ".sha256"))) {
                throw new IOException("Recording engine file is missing or changed; existing files were preserved: " + relative);
            }
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Engine preparation interrupted");
    }

    private static void deleteStaging(Path tools, Path staging) throws IOException {
        Path checked = staging.toAbsolutePath().normalize();
        if (!checked.getParent().equals(tools) || !checked.getFileName().toString().startsWith(".ffmpeg-prepare-")) {
            throw new IOException("Refusing to clean a path outside the engine staging directory");
        }
        Files.walkFileTree(checked, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Bounds bytes while downloading; cancellation closes the file before staging cleanup. */
    private static final class LimitedDownload implements HttpResponse.BodySubscriber<Path> {
        private final Path path;
        private final long limit;
        private final FileChannel output;
        private final CompletableFuture<Path> completion = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long received;
        private boolean ended;

        private LimitedDownload(Path path, long limit) throws IOException {
            this.path = path;
            this.limit = limit;
            this.output = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        @Override public CompletionStage<Path> getBody() { return completion; }
        @Override public synchronized void onSubscribe(Flow.Subscription subscription) {
            if (ended || this.subscription != null) { subscription.cancel(); return; }
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (ended) return;
            try {
                for (ByteBuffer buffer : buffers) {
                    received += buffer.remaining();
                    if (received > limit) throw new IOException("Engine download exceeds 300 MB");
                    while (buffer.hasRemaining()) output.write(buffer);
                }
                subscription.request(1);
            } catch (IOException exception) { fail(exception); }
        }
        @Override public void onError(Throwable failure) { fail(failure); }
        @Override public synchronized void onComplete() {
            if (ended) return;
            try { output.force(true); output.close(); }
            catch (IOException exception) { fail(exception); return; }
            ended = true;
            completion.complete(path);
        }
        private synchronized void fail(Throwable failure) {
            if (ended) return;
            ended = true;
            if (subscription != null) subscription.cancel();
            try { output.close(); } catch (IOException close) { failure.addSuppressed(close); }
            completion.completeExceptionally(failure);
        }
        private void close() { fail(new IOException("Engine download cancelled")); }
    }
}
