package dev.wacamera.recorder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Resolves local executables only; it never downloads or invokes a shell. */
public final class ExecutableResolver {
    private ExecutableResolver() {}

    public static Path resolveFfmpeg(Path configured) throws IOException {
        if (configured != null && !configured.toString().isBlank()) {
            if (Files.isRegularFile(configured)) return requireFile(configured);
            if (!configured.isAbsolute() && configured.getParent() == null) {
                Optional<Path> executable = findOnPath(configured.toString());
                if (executable.isPresent()) return executable.get();
            }
            return requireFile(configured);
        }
        return findOnPath("ffmpeg").orElseThrow(() -> new IOException("FFmpeg was not found. Set its executable path in WaCamera settings."));
    }

    public static Path resolveFfprobe(Path ffmpeg) throws IOException {
        String suffix = isWindows() ? ".exe" : "";
        Path sibling = ffmpeg.toAbsolutePath().getParent().resolve("ffprobe" + suffix);
        if (Files.isRegularFile(sibling)) return sibling;
        return findOnPath("ffprobe").orElseThrow(() -> new IOException("ffprobe was not found beside FFmpeg or on PATH."));
    }

    public static Optional<Path> findOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null) return Optional.empty();
        String suffix = isWindows() && !executable.toLowerCase(Locale.ROOT).endsWith(".exe") ? ".exe" : "";
        for (String directory : path.split(Pattern.quote(System.getProperty("path.separator")))) {
            if (directory.isBlank()) continue;
            try {
                Path candidate = Path.of(directory.replace("\"", "")).resolve(executable + suffix).toAbsolutePath();
                if (Files.isRegularFile(candidate)) return Optional.of(candidate);
            } catch (RuntimeException ignored) { /* Ignore malformed PATH entries. */ }
        }
        return Optional.empty();
    }

    static Path requireFile(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) throw new IOException("Executable was not found: " + absolute);
        return absolute;
    }

    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }
}
