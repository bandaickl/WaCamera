package dev.wacamera.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Ten persistent slots. A shot's display name never determines its storage path. */
public final class ShotPatternRepository {
    public static final int SLOT_COUNT = 10;
    private final Path directory;

    public ShotPatternRepository(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    public Path save(int number, ShotDefinition shot) throws IOException {
        return ShotJsonStorage.save(pathFor(number), shot);
    }

    public ShotDefinition load(int number) throws IOException {
        return ShotJsonStorage.load(pathFor(number));
    }

    /** Includes every slot in numerical order. Invalid occupied files are reported instead of hidden. */
    public List<Slot> list() throws IOException {
        List<Slot> slots = new ArrayList<>(SLOT_COUNT);
        for (int number = 1; number <= SLOT_COUNT; ++number) {
            Path file = pathFor(number);
            boolean occupied = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
            slots.add(new Slot(number, occupied ? ShotJsonStorage.load(file).name() : "", occupied));
        }
        return List.copyOf(slots);
    }

    private Path pathFor(int number) {
        if (number < 1 || number > SLOT_COUNT)
            throw new IllegalArgumentException("Pattern slot must be between 1 and " + SLOT_COUNT);
        return directory.resolve(String.format(Locale.ROOT, "pattern-%02d.json", number));
    }

    public record Slot(int number, String name, boolean occupied) { }
}
