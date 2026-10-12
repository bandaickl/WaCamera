package dev.wacamera.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShotPlaybackSpeedTest {
    @TempDir Path directory;

    private static ShotDefinition shot(double speed) {
        return new ShotDefinition(2, "sample", "minecraft:overworld", 20, 3, "smooth", "constant",
                "target", 85, List.of(new ShotPoint("a", 1.25, 67.5, -3.125, 45.5f, -22.25f)),
                new Vec3d(10, 64, -8), speed);
    }

    @Test void finiteFractionalSpeedAndInclusiveEndpointsAreAccepted() {
        for (double speed : new double[]{1, Math.nextUp(1d), 1.5, 12.75, 120})
            assertEquals(speed, shot(speed).playbackSpeed());
        for (double speed : new double[]{0, -1, Math.nextDown(1d), Math.nextUp(120d),
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> shot(speed), Double.toString(speed));
    }

    @Test void oldConstructorUpgradesVersionOneAndDefaultsToNormalSpeed() {
        ShotDefinition source = shot(1);
        for (int version : new int[]{1, 2}) {
            assertEquals(source, new ShotDefinition(version, source.name(), source.dimension(), source.durationSeconds(),
                    source.countdownSeconds(), source.positionInterpolation(), source.speedMode(), source.lookMode(),
                    source.fovDegrees(), source.points(), source.target()));
        }
        assertEquals(2, ShotDefinition.CURRENT_SCHEMA_VERSION);
        assertEquals(2, ShotDefinition.defaults(source.dimension(), source.points()).schemaVersion());
        assertThrows(IllegalArgumentException.class, () -> new ShotDefinition(3, source.name(), source.dimension(),
                20, 3, "smooth", "constant", "target", 85, source.points(), source.target()));
    }

    @Test void namedShotsPersistPlaybackSpeed() throws IOException {
        ShotRepository repository = new ShotRepository(directory);
        ShotDefinition source = shot(17.625);
        Path path = repository.save(source);
        assertEquals(source, new ShotRepository(directory).load(source.name()));
        JsonObject saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        assertEquals(2, saved.get("schemaVersion").getAsInt());
        assertEquals(17.625, saved.get("playbackSpeed").getAsDouble());
    }

    @Test void legacyJsonMigratesToNormalSpeedWithoutRewritingSource() throws IOException {
        JsonObject legacy = new Gson().toJsonTree(shot(6)).getAsJsonObject();
        legacy.addProperty("schemaVersion", 1);
        legacy.remove("playbackSpeed");
        Path path = directory.resolve("sample.json");
        Files.writeString(path, legacy.toString());
        String before = Files.readString(path);
        ShotDefinition loaded = new ShotRepository(directory).load("sample");
        assertEquals(shot(1), loaded);
        assertEquals(before, Files.readString(path));
        new ShotRepository(directory).save(loaded);
        assertEquals(2, JsonParser.parseString(Files.readString(path)).getAsJsonObject().get("schemaVersion").getAsInt());
    }

    @Test void schemaTwoRequiresNumericFiniteSpeedAndRejectsInvalidValues() throws IOException {
        ShotRepository repository = new ShotRepository(directory);
        Path path = repository.save(shot(2.5));
        JsonObject missing = new Gson().toJsonTree(shot(2.5)).getAsJsonObject();
        missing.remove("playbackSpeed");
        Files.writeString(path, missing.toString());
        assertThrows(IOException.class, () -> repository.load("sample"));
        String valid = new Gson().toJson(shot(2.5));
        for (String invalid : List.of("0", "0.99", "120.0001", "null", "\"NaN\"", "NaN", "1e309", "\"2.5\"", "true")) {
            Files.writeString(path, valid.replace("\"playbackSpeed\":2.5", "\"playbackSpeed\":" + invalid));
            assertThrows(IOException.class, () -> repository.load("sample"), invalid);
        }
    }
}
