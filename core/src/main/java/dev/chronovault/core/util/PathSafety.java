package dev.chronovault.core.util;

import dev.chronovault.core.ChronoException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PathSafety {
    private PathSafety() {}

    public static String normalizeSlash(String path) {
        if (path == null) return null;
        return path.replace('\\', '/')
            .replaceAll("//+", "/")
            .replaceFirst("^/+", "");
    }

    public static boolean containsEscapes(String relPath) {
        if (relPath == null || relPath.isBlank()) return false;
        if (Path.of(relPath).isAbsolute()) return true;
        String normalized = normalizeSlash(relPath);
        if (normalized.isEmpty()) return false;
        Path p = Path.of(normalized);
        for (Path part : p) {
            String s = part.toString();
            if (s.equals("..") || s.equals(".")) return true;
            if (s.isEmpty()) return true;
        }
        if (Path.of(normalized).isAbsolute()) return true;
        return false;
    }

    /** Returns true if the relative path is safe (no traversal, no absolute, no empty parts). */
    public static boolean isSafeRelativePath(String relPath) {
        if (relPath == null || relPath.isBlank()) return false;
        if (Path.of(relPath).isAbsolute() || Path.of(relPath).getRoot() != null) return false;
        String normalized = normalizeSlash(relPath);
        return !normalized.isEmpty() && !containsEscapes(normalized);
    }

    /** Join a root and a validated relative path, throwing if escapes are detected. */
    public static Path resolveInside(Path root, String relPath) {
        if (!isSafeRelativePath(relPath)) {
            throw new ChronoException.UnsafePathException("Unsafe path in snapshot/restore: " + relPath);
        }
        Path resolved = root.resolve(normalizeSlash(relPath)).normalize();
        return resolved;
    }

    /** Ensure a directory exists and is within root; detects symlink escape during creation. */
    public static void validateDirectory(Path dir, Path root) throws IOException {
        Path canonicalRoot = root.toAbsolutePath().normalize();
        Path canonicalDir = dir.toAbsolutePath().normalize();
        if (!canonicalDir.startsWith(canonicalRoot)) {
            throw new ChronoException.UnsafePathException("Path escapes project: " + dir);
        }
        Files.createDirectories(canonicalDir);
    }

    public static boolean isWithin(Path candidate, Path root) {
        return candidate.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize());
    }
}