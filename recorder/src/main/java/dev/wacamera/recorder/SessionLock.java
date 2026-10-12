package dev.wacamera.recorder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** OS-backed lease prevents recovery from touching an active encoder session. */
final class SessionLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private SessionLock(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }

    static SessionLock acquire(Path session) throws IOException {
        FileChannel channel = FileChannel.open(session.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Recording session is still active");
            return new SessionLock(channel, lock);
        } catch (IOException | OverlappingFileLockException e) {
            channel.close();
            throw new IOException("Recording session is still active or locked", e);
        }
    }

    static boolean isActive(Path session) {
        if (!Files.exists(session.resolve("session.lock"))) return false;
        try (SessionLock ignored = acquire(session)) { return false; }
        catch (IOException e) { return true; }
    }

    @Override public void close() {
        try { lock.release(); } catch (IOException ignored) {}
        try { channel.close(); } catch (IOException ignored) {}
    }
}
