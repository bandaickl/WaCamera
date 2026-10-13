package dev.wacamera.recorder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.UUID;

/** OS-backed lease shared by recording, recovery and explicit deletion. */
final class SessionLock implements AutoCloseable {
    private final FileChannel guardChannel;
    private final FileLock guardLock;
    private final FileChannel sessionChannel;
    private final FileLock sessionLock;

    private SessionLock(FileChannel guardChannel, FileLock guardLock, FileChannel sessionChannel, FileLock sessionLock) {
        this.guardChannel = guardChannel;
        this.guardLock = guardLock;
        this.sessionChannel = sessionChannel;
        this.sessionLock = sessionLock;
    }

    static SessionLock acquire(Path requested) throws IOException {
        Path session = canonicalSession(requested);
        FileChannel guard = null, inside = null;
        try {
            // Keep a stable lock outside the session. Windows requires the inner lock handle
            // to close before its file/directory can be removed; this lease bridges that gap.
            // Never delete the guard file: replacing a locked inode would permit two owners.
            guard = openLockFile(guardPath(session));
            FileLock guardLock = guard.tryLock();
            if (guardLock == null) throw new IOException("Recording session is still active");
            requirePlainDirectory(session);
            inside = openLockFile(session.resolve("session.lock"));
            FileLock sessionLock = inside.tryLock();
            if (sessionLock == null) throw new IOException("Recording session is still active");
            return new SessionLock(guard, guardLock, inside, sessionLock);
        } catch (IOException | RuntimeException e) {
            closeQuietly(inside);
            closeQuietly(guard);
            throw new IOException("Recording session is still active or locked", e);
        }
    }

    static boolean isActive(Path requested) {
        try {
            Path session = canonicalSession(requested);
            if (!Files.exists(session.resolve("session.lock"), LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(guardPath(session), LinkOption.NOFOLLOW_LINKS)) return false;
            try (SessionLock ignored = acquire(session)) { return false; }
        }
        catch (IOException e) { return true; }
    }

    /** The outer OS lease remains held while the caller removes the inner file and session. */
    void closeSessionFileForDeletion() throws IOException { sessionChannel.close(); }

    private static Path canonicalSession(Path requested) throws IOException {
        Path session = requested.toAbsolutePath().normalize();
        requirePlainDirectory(session);
        // Normal recording may use a launcher/output ancestor reached through a junction.
        // Canonicalize it for both locks (including Windows 8.3/case aliases), while deletion
        // independently applies the stricter all-ancestor NOFOLLOW policy before acquiring.
        Path canonical = session.toRealPath();
        validateDirectory(canonical);
        return canonical;
    }

    /** Reject every linked/reparse ancestor, including Windows junctions (isOther, not isSymbolicLink). */
    static Path validateDirectory(Path requested) throws IOException {
        Path directory = requested.toAbsolutePath().normalize();
        Path current = directory.getRoot();
        requirePlainDirectory(current);
        for (Path component : directory) {
            current = current.resolve(component);
            requirePlainDirectory(current);
        }
        return directory;
    }

    private static void requirePlainDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther())
            throw new IOException("Linked or invalid recovery directory: " + directory);
    }

    private static Path guardPath(Path session) {
        // Session names are generated ASCII IDs. Folding also gives Windows case aliases the
        // same lock; harmless over-serialization is preferable on case-sensitive file systems.
        String name = session.getFileName().toString().toLowerCase(Locale.ROOT);
        String id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
        return session.getParent().resolve(".session-" + id + ".lock");
    }

    private static FileChannel openLockFile(Path path) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther())
                throw new IOException("Linked or invalid recording lock: " + path);
        } catch (NoSuchFileException missing) { /* Created atomically below; never truncate an existing lock. */ }
        return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) try { channel.close(); } catch (IOException ignored) {}
    }

    @Override public void close() {
        closeQuietly(sessionChannel);
        closeQuietly(guardChannel);
    }
}
