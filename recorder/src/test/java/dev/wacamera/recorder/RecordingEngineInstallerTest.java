package dev.wacamera.recorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class RecordingEngineInstallerTest {
    @TempDir Path directory;
    private static final String ROOT = "ffmpeg-9.0.2-essentials_build/";

    @Test void traversalAbsoluteDriveAndBackslashPathsAreRejected() {
        for (String name : List.of("../escape.exe", ROOT + "../../escape.exe", "..\\escape.exe",
                "/absolute.exe", "C:/absolute.exe", "C:relative.exe", "\\\\host\\share\\evil.exe",
                "root//entry", "root/./entry", "root/.. /escape.exe", "root/dir. /escape.exe")) {
            assertThrows(IOException.class, () -> RecordingEngineInstaller.safeEntryPath(directory, name), name);
        }
    }

    @Test void onlyRequiredExecutablesAndOriginalNoticesAreExtracted() throws IOException {
        Map<String, String> entries = validEntries();
        entries.put(ROOT + "bin/ffplay.exe", "unneeded executable");
        entries.put(ROOT + "doc/ffmpeg.html", "documentation");
        Path archive = zip(entries);
        Path output = directory.resolve("output");
        RecordingEngineInstaller.extractArchive(archive, output);
        assertEquals("MZ ffmpeg", Files.readString(output.resolve("bin/ffmpeg.exe")));
        assertEquals("MZ ffprobe", Files.readString(output.resolve("bin/ffprobe.exe")));
        assertEquals("original license", Files.readString(output.resolve("LICENSE.txt")));
        assertEquals("original readme", Files.readString(output.resolve("README.txt")));
        assertFalse(Files.exists(output.resolve("bin/ffplay.exe")));
        assertFalse(Files.exists(output.resolve("doc")));
    }

    @Test void maliciousUnselectedEntryIsRejectedToo() throws IOException {
        Map<String, String> entries = validEntries();
        entries.put("../escape.txt", "malicious");
        Path archive = zip(entries);
        assertThrows(IOException.class, () -> RecordingEngineInstaller.extractArchive(archive, directory.resolve("output")));
        assertFalse(Files.exists(directory.resolve("escape.txt")));
    }

    @Test void missingLicenseAndDuplicateOutputAreRejected() throws IOException {
        Map<String, String> missing = validEntries();
        missing.remove(ROOT + "LICENSE");
        Path missingArchive = zip(missing);
        assertThrows(IOException.class, () -> RecordingEngineInstaller.extractArchive(missingArchive, directory.resolve("missing")));
        Map<String, String> duplicate = validEntries();
        duplicate.put(ROOT + "license.txt", "duplicate");
        Path duplicateArchive = zip(duplicate);
        assertThrows(IOException.class, () -> RecordingEngineInstaller.extractArchive(duplicateArchive, directory.resolve("duplicate")));
    }

    @Test void hashMismatchIsRejectedBeforeExtraction() throws IOException {
        Path file = directory.resolve("download.zip");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", RecordingEngineInstaller.sha256(file));
        assertDoesNotThrow(() -> RecordingEngineInstaller.verifyArchive(file,
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        assertThrows(IOException.class, () -> RecordingEngineInstaller.verifyArchive(file, RecordingEngineInstaller.ARCHIVE_SHA256));
    }

    @Test void preexistingExtractionDirectoryIsNeverOverwritten() throws IOException {
        Path output = Files.createDirectory(directory.resolve("existing"));
        Path marker = Files.writeString(output.resolve("user-file.txt"), "keep");
        Path archive = zip(validEntries());
        assertThrows(IOException.class, () -> RecordingEngineInstaller.extractArchive(archive, output));
        assertEquals("keep", Files.readString(marker));
    }

    private Map<String, String> validEntries() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put(ROOT + "bin/ffmpeg.exe", "MZ ffmpeg");
        result.put(ROOT + "bin/ffprobe.exe", "MZ ffprobe");
        result.put(ROOT + "LICENSE", "original license");
        result.put(ROOT + "README.txt", "original readme");
        return result;
    }

    private Path zip(Map<String, String> entries) throws IOException {
        Path archive = Files.createTempFile(directory, "engine-", ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return archive;
    }
}
