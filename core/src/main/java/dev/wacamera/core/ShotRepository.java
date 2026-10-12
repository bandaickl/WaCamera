package dev.wacamera.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Unlimited named UTF-8 JSON shots, with validated names and atomic replacement. */
public final class ShotRepository {
    private static final Pattern INVALID_NAME = Pattern.compile("[<>:\"/\\\\|?*\\p{Cntrl}]");
    private static final Set<String> RESERVED_NAMES = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private final Path directory;

    public ShotRepository(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    public Path save(ShotDefinition shot) throws IOException {
        Objects.requireNonNull(shot, "shot");
        return ShotJsonStorage.save(pathFor(shot.name()), shot);
    }

    public ShotDefinition load(String name) throws IOException {
        ShotDefinition shot = ShotJsonStorage.load(pathFor(name));
        if (!name.equals(shot.name())) throw new IOException("Shot name does not match its file name: " + name);
        return shot;
    }

    public List<String> list() throws IOException {
        if (!Files.exists(directory)) return List.of();
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - 5)).filter(ShotRepository::isValidName)
                    .sorted().toList();
        }
    }

    private Path pathFor(String name) {
        if (!isValidName(name)) throw new IllegalArgumentException("Invalid shot file name");
        Path result = directory.resolve(name + ".json").normalize();
        if (!result.getParent().equals(directory)) throw new IllegalArgumentException("Shot path leaves its directory");
        return result;
    }

    private static boolean isValidName(String name) {
        if (name == null || name.isBlank() || name.length() > 80 || INVALID_NAME.matcher(name).find()
                || name.endsWith(".") || name.endsWith(" ") || name.equals(".") || name.equals("..")) return false;
        String stem = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        return !RESERVED_NAMES.contains(stem);
    }
}
