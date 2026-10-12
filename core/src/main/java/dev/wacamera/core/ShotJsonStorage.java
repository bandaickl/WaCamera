package dev.wacamera.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Shared schema migration, strict field validation and atomic UTF-8 storage for both repositories. */
final class ShotJsonStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final long MAX_FILE_BYTES = 1_048_576;

    private ShotJsonStorage() { }

    static Path save(Path destination, ShotDefinition shot) throws IOException {
        Objects.requireNonNull(shot, "shot");
        Path directory = destination.getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "shot-", ".json.tmp");
        try {
            byte[] bytes = (GSON.toJson(shot) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("This filesystem cannot atomically save shots; previous settings were preserved", exception);
            }
            return destination;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static ShotDefinition load(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Shot file does not exist or is not a regular file: " + file.getFileName());
        if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("Shot file is too large");
        try {
            JsonElement json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            validateAndMigrate(json);
            return GSON.fromJson(json, ShotDefinition.class);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid shot file: " + file.getFileName() + ": " + exception.getMessage(), exception);
        }
    }

    private static void validateAndMigrate(JsonElement value) {
        if (!value.isJsonObject()) throw new IllegalArgumentException("Shot must be a JSON object");
        JsonObject object = value.getAsJsonObject();
        require(object, "schemaVersion", "name", "dimension", "durationSeconds", "countdownSeconds",
                "positionInterpolation", "speedMode", "lookMode", "fovDegrees", "points");
        requireStrings(object, "name", "dimension", "positionInterpolation", "speedMode", "lookMode");
        requireNumbers(object, "schemaVersion", "durationSeconds", "countdownSeconds", "fovDegrees");
        int version = object.get("schemaVersion").getAsBigDecimal().intValueExact();
        if (version == 1) {
            object.addProperty("schemaVersion", ShotDefinition.CURRENT_SCHEMA_VERSION);
            object.addProperty("playbackSpeed", 1);
        } else if (version == ShotDefinition.CURRENT_SCHEMA_VERSION) {
            require(object, "playbackSpeed");
            requireNumbers(object, "playbackSpeed");
        } else throw new IllegalArgumentException("Unsupported shot schema version: " + version);
        object.get("countdownSeconds").getAsBigDecimal().intValueExact();
        if (!object.get("points").isJsonArray()) throw new IllegalArgumentException("Points must be an array");
        for (JsonElement point : object.getAsJsonArray("points")) {
            if (!point.isJsonObject()) throw new IllegalArgumentException("Point must be an object");
            require(point.getAsJsonObject(), "id", "x", "y", "z", "yawDegrees", "pitchDegrees");
            requireStrings(point.getAsJsonObject(), "id");
            requireNumbers(point.getAsJsonObject(), "x", "y", "z", "yawDegrees", "pitchDegrees");
        }
        if (object.has("target") && !object.get("target").isJsonNull()) {
            if (!object.get("target").isJsonObject()) throw new IllegalArgumentException("Target must be an object");
            require(object.getAsJsonObject("target"), "x", "y", "z");
            requireNumbers(object.getAsJsonObject("target"), "x", "y", "z");
        }
    }

    private static void require(JsonObject object, String... names) {
        for (String name : names) {
            if (!object.has(name) || object.get(name).isJsonNull())
                throw new IllegalArgumentException("Missing required field: " + name);
        }
    }

    private static void requireStrings(JsonObject object, String... names) {
        for (String name : names) {
            JsonElement value = object.get(name);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Field must be a string: " + name);
        }
    }

    private static void requireNumbers(JsonObject object, String... names) {
        for (String name : names) {
            JsonElement value = object.get(name);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException("Field must be a number: " + name);
        }
    }
}
