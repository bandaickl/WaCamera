package dev.wacamera.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Local machine settings; never included in a shared shot. */
public final class ClientSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public String ffmpeg = "ffmpeg";
    public String audioHelper = "";
    public String quality = "720p30";
    public boolean audio = false;
    public String lastShot = "";

    public static ClientSettings load(Path root) {
        // Preserve saved audio choices and external helper paths, including old extracted paths.
        Path file = root.resolve("settings.json");
        try {
            if (Files.isRegularFile(file)) {
                ClientSettings s = GSON.fromJson(Files.readString(file), ClientSettings.class);
                if (s != null && s.ffmpeg != null && s.audioHelper != null && s.quality != null) return s;
            }
        } catch (IOException | RuntimeException ignored) { }
        return new ClientSettings();
    }

    public void save(Path root) throws IOException {
        Files.createDirectories(root);
        Path tmp = root.resolve("settings.json.tmp");
        Files.writeString(tmp, GSON.toJson(this));
        try { Files.move(tmp, root.resolve("settings.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, root.resolve("settings.json"), StandardCopyOption.REPLACE_EXISTING); }
    }
}
