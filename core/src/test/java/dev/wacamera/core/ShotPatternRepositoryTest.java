package dev.wacamera.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class ShotPatternRepositoryTest {
    @TempDir Path directory;

    private static ShotDefinition named(String name, double speed) {
        return new ShotDefinition(2, name, "minecraft:the_nether", 120.5, 4, "smooth", "constant",
                "target", 92.5f, List.of(new ShotPoint("first", 12.25, 80.5, -300.75, 179.25f, -32.5f),
                new ShotPoint("second", -45.875, 97.25, 303.125, -156.75f, 48.5f)),
                new Vec3d(-22.5, 64.125, 19.75), speed);
    }

    @Test void newRepositoryListsAllTenEmptySlotsInOrderWithoutCreatingFiles() throws IOException {
        Path nonexistent = directory.resolve("patterns");
        List<ShotPatternRepository.Slot> slots = new ShotPatternRepository(nonexistent).list();
        assertEquals(10, ShotPatternRepository.SLOT_COUNT);
        assertEquals(IntStream.rangeClosed(1, 10).mapToObj(n -> new ShotPatternRepository.Slot(n, "", false)).toList(), slots);
        assertThrows(UnsupportedOperationException.class, () -> slots.clear());
        assertFalse(Files.exists(nonexistent));
        assertThrows(IOException.class, () -> new ShotPatternRepository(nonexistent).load(1));
    }

    @Test void allTenSlotsRoundTripEveryFieldAfterReopening() throws IOException {
        ShotPatternRepository repository = new ShotPatternRepository(directory);
        for (int number = 1; number <= 10; ++number) {
            ShotDefinition original = named("日本語 パターン " + number, number + .5);
            Path saved = repository.save(number, original);
            assertEquals(String.format(java.util.Locale.ROOT, "pattern-%02d.json", number), saved.getFileName().toString());
        }
        ShotPatternRepository reopened = new ShotPatternRepository(directory);
        List<ShotPatternRepository.Slot> slots = reopened.list();
        assertEquals(10, slots.size());
        for (int number = 1; number <= 10; ++number) {
            ShotDefinition original = named("日本語 パターン " + number, number + .5);
            assertEquals(original, reopened.load(number));
            assertEquals(new ShotPatternRepository.Slot(number, original.name(), true), slots.get(number - 1));
        }
        try (var files = Files.list(directory)) { assertEquals(10, files.count()); }
    }

    @Test void duplicateAndPathLikeDisplayNamesCannotOverwriteOtherSlots() throws IOException {
        ShotPatternRepository repository = new ShotPatternRepository(directory);
        Path first = repository.save(1, named("A", 2.25));
        Path second = repository.save(2, named("A", 10));
        String firstBytes = Files.readString(first);
        assertNotEquals(first, second);
        repository.save(2, named("../A:夜\\パターン", 120));
        assertEquals(firstBytes, Files.readString(first));
        assertEquals(named("A", 2.25), repository.load(1));
        assertEquals(named("../A:夜\\パターン", 120), repository.load(2));
        try (var files = Files.list(directory)) { assertEquals(2, files.count()); }
    }

    @Test void invalidSlotNumbersCannotChangeExistingData() throws IOException {
        ShotPatternRepository repository = new ShotPatternRepository(directory);
        Path first = repository.save(1, named("original", 4.5));
        String before = Files.readString(first);
        for (int slot : new int[]{Integer.MIN_VALUE, -1, 0, 11, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> repository.save(slot, named("replacement", 2)));
            assertThrows(IllegalArgumentException.class, () -> repository.load(slot));
        }
        assertEquals(before, Files.readString(first));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void malformedOccupiedSlotsAreReportedAndDoNotAppearEmpty() throws IOException {
        ShotPatternRepository repository = new ShotPatternRepository(directory);
        Path file = repository.save(7, named("saved", 1));
        Files.writeString(file, "{broken");
        assertThrows(IOException.class, repository::list);
        assertThrows(IOException.class, () -> repository.load(7));
        assertEquals("{broken", Files.readString(file));
    }

    @Test void legacySlotJsonAlsoMigratesToSchemaTwoAtNormalSpeed() throws IOException {
        JsonObject legacy = new Gson().toJsonTree(named("以前の設定", 8)).getAsJsonObject();
        legacy.addProperty("schemaVersion", 1);
        legacy.remove("playbackSpeed");
        Files.writeString(directory.resolve("pattern-10.json"), legacy.toString());
        ShotPatternRepository repository = new ShotPatternRepository(directory);
        assertEquals(named("以前の設定", 1), repository.load(10));
        assertEquals(new ShotPatternRepository.Slot(10, "以前の設定", true), repository.list().get(9));
    }

    @Test void namedRepositoryStillSupportsMoreThanTenShots() throws IOException {
        ShotRepository repository = new ShotRepository(directory.resolve("shots"));
        for (int number = 1; number <= 12; ++number) repository.save(named("shot-" + number, 2.5));
        assertEquals(12, repository.list().size());
        assertEquals(named("shot-12", 2.5), repository.load("shot-12"));
    }
}
