package dev.wacamera.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Local machine settings; never included in a shared shot. */
public final class ClientSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public String ffmpeg = "ffmpeg";
    public String audioHelper = "";
    public String quality = "720p30";
    public boolean audio;
    public String lastShot = "";

    public static ClientSettings load(Path root) {
        // Refresh the managed binary before returning existing preferences as well.
        // Alpha releases may change the native payload without changing this directory name.
        Path helper = refreshBundledHelper(root);
        Path file = root.resolve("settings.json");
        try {
            if (Files.isRegularFile(file)) {
                ClientSettings s = GSON.fromJson(Files.readString(file), ClientSettings.class);
                if (s != null && s.ffmpeg != null && s.audioHelper != null && s.quality != null) return s;
            }
        } catch (IOException | RuntimeException ignored) { }
        ClientSettings s = new ClientSettings();
        if (Files.isRegularFile(helper)) {
            s.audioHelper = helper.toString();
            s.audio = System.getProperty("os.name", "").startsWith("Windows");
        }
        return s;
    }

    private static Path refreshBundledHelper(Path root) {
        Path helper = root.resolve("tools/0.1.0-alpha.1/wacamera-audio.exe").toAbsolutePath().normalize();
        if (!System.getProperty("os.name", "").startsWith("Windows")) return helper;
        Path temporary = null;
        try (var resource = ClientSettings.class.getResourceAsStream("/native/windows-x86_64/wacamera-audio.exe")) {
            if (resource == null) return helper;
            byte[] bundled = resource.readAllBytes();
            byte[] expectedHash = sha256(bundled);
            if (Files.isRegularFile(helper) && MessageDigest.isEqual(expectedHash, sha256(Files.readAllBytes(helper)))) return helper;
            try (var processes = ProcessHandle.allProcesses()) {
                boolean inUse = processes.anyMatch(process -> process.info().command().map(command -> {
                    try { return Path.of(command).toAbsolutePath().normalize().toString().equalsIgnoreCase(helper.toString()); }
                    catch (RuntimeException ignored) { return false; }
                }).orElse(false));
                if (inUse) throw new IOException("Audio helper update is pending. Stop recordings in other Minecraft instances, then restart this game.");
            }
            Files.createDirectories(helper.getParent());
            temporary = helper.resolveSibling("wacamera-audio-" + UUID.randomUUID() + ".tmp");
            Files.write(temporary, bundled);
            if (!MessageDigest.isEqual(expectedHash, sha256(Files.readAllBytes(temporary)))) throw new IOException("Audio helper integrity verification failed");
            // Same-directory atomic replacement leaves the old executable intact on failure.
            // Windows also refuses replacement if a process starts using it after the check above.
            Files.move(temporary, helper, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
        } catch (IOException | RuntimeException e) { WaCameraClient.error(e); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
        return helper;
    }

    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    public void save(Path root) throws IOException {
        Files.createDirectories(root);
        Path tmp = root.resolve("settings.json.tmp");
        Files.writeString(tmp, GSON.toJson(this));
        try { Files.move(tmp, root.resolve("settings.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, root.resolve("settings.json"), StandardCopyOption.REPLACE_EXISTING); }
    }
}
