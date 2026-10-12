package dev.wacamera.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ShotRepositoryTest {
    @TempDir Path directory;

    private static ShotDefinition named(String name, double duration) {
        return new ShotDefinition(1, name, "minecraft:overworld", duration, 3,
                "linear", "constant", "self", 70,
                List.of(new ShotPoint("a", 1, 2, 3, 179, 10)), null);
    }

    @Test void unicodeAndSpaceNamesRoundTripAndReplacementLeavesNoTemporaryFiles() throws IOException {
        ShotRepository repository = new ShotRepository(directory.resolve("日本語 保存先"));
        ShotDefinition original = named("冒頭 ショット", 10);
        repository.save(original);
        assertEquals(original, repository.load(original.name()));
        ShotDefinition replacement = named(original.name(), 20);
        repository.save(replacement);
        assertEquals(replacement, repository.load(original.name()));
        assertEquals(List.of(original.name()), repository.list());
        try (var files = Files.list(directory.resolve("日本語 保存先"))) {
            assertEquals(1, files.count());
        }
    }

    @Test void repositoryRejectsTraversalAndWindowsDeviceNames() {
        ShotRepository repository = new ShotRepository(directory);
        for (String name : List.of("../escape", "..\\escape", "CON", "nul.txt", "bad:name", "trailing.", "trail ")) {
            assertThrows(IllegalArgumentException.class, () -> repository.save(named(name, 10)), name);
            assertThrows(IllegalArgumentException.class, () -> repository.load(name), name);
        }
    }

    @Test void missingRepositoryIsAnEmptyList() throws IOException {
        assertEquals(List.of(), new ShotRepository(directory.resolve("new-directory")).list());
    }

    @Test void corruptedUnknownVersionInvalidNumbersAndMissingFieldsAreRejected() throws IOException {
        ShotRepository repository = new ShotRepository(directory);
        Path file = repository.save(named("sample", 10));
        String valid = Files.readString(file);
        for (String invalid : List.of("{", "null", "[]",
                valid.replace("\"schemaVersion\": 2", "\"schemaVersion\": 3"),
                valid.replace("\"schemaVersion\": 2", "\"schemaVersion\": 1.5"),
                valid.replace("\"countdownSeconds\": 3", "\"countdownSeconds\": 3.5"),
                valid.replace("\"durationSeconds\": 10.0", "\"durationSeconds\": -1"),
                valid.replace("\"x\": 1.0", "\"x\": \"NaN\""),
                valid.replace("\"x\": 1.0", "\"x\": \"1.0\""),
                valid.replace("\"id\": \"a\"", "\"id\": true"),
                valid.replace("\"pitchDegrees\": 10.0", "\"pitchDegrees\": null"))) {
            Files.writeString(file, invalid);
            assertThrows(IOException.class, () -> repository.load("sample"), invalid);
        }
    }

    @Test void definitionsValidateConstraintsAndCopyPointLists() {
        List<ShotPoint> mutable = new ArrayList<>(List.of(new ShotPoint("a", 0, 0, 0, 0, 0)));
        ShotDefinition definition = ShotDefinition.defaults("minecraft:overworld", mutable);
        mutable.clear();
        assertEquals(1, definition.points().size());
        assertThrows(UnsupportedOperationException.class, () -> definition.points().clear());
        assertThrows(IllegalArgumentException.class, () -> ShotDefinition.defaults("invalid", definition.points()));
        assertThrows(IllegalArgumentException.class, () -> ShotDefinition.defaults("minecraft:overworld", List.of()));
        assertThrows(IllegalArgumentException.class, () -> ShotDefinition.defaults("minecraft:overworld",
                List.of(definition.points().get(0), definition.points().get(0))));
        assertThrows(IllegalArgumentException.class, () -> named("test", 3601));
        assertThrows(IllegalArgumentException.class, () -> new ShotPoint("a", Double.NaN, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ShotDefinition(1, "test", "minecraft:overworld",
                10, 3, "linear", "constant", "target", 70, definition.points(), null));
    }

    @Test void pointAndTimeLimitsAcceptEndpointsAndRejectValuesOutsideThem() {
        List<ShotPoint> points = IntStream.range(0, 64)
                .mapToObj(index -> new ShotPoint("p" + index, index, 0, 0, 0, 0)).toList();
        assertEquals(64, ShotDefinition.defaults("minecraft:overworld", points).points().size());
        ArrayList<ShotPoint> tooMany = new ArrayList<>(points);
        tooMany.add(new ShotPoint("p64", 64, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ShotDefinition.defaults("minecraft:overworld", tooMany));
        assertEquals(1, named("short", 1).durationSeconds());
        assertEquals(3600, named("long", 3600).durationSeconds());
        assertThrows(IllegalArgumentException.class, () -> named("short", .99));
        for (int countdown : new int[]{-1, 11}) {
            assertThrows(IllegalArgumentException.class, () -> new ShotDefinition(1, "test", "minecraft:overworld",
                    10, countdown, "linear", "constant", "registered", 70, points, null));
        }
    }
}
