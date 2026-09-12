package dev.chronovault.core.util;

import dev.chronovault.core.ChronoException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
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
        validateWritePath(dir, root);
        Files.createDirectories(dir.toAbsolutePath().normalize());
    }

    /**
     * Verifies that writing through {@code target} cannot escape {@code root} via a
     * symlinked ancestor. The lexical check is not enough: a symlink placed inside the
     * project (e.g. {@code root/link -> /elsewhere}) would otherwise let materialization
     * call {@code Files.createDirectories(parent)} and write files outside the project.
     * Every existing ancestor of the target is resolved to its real path, and any escape
     * raises {@link ChronoException.UnsafePathException} before a single byte is written.
     */
    public static void validateWritePath(Path target, Path root) throws IOException {
        Path abs = target.toAbsolutePath().normalize();
        Path canonicalRoot = root.toAbsolutePath().normalize();
        if (!abs.startsWith(canonicalRoot)) {
            throw new ChronoException.UnsafePathException("Path escapes project: " + target);
        }
        Path realRoot;
        try {
            realRoot = canonicalRoot.toRealPath();
        } catch (NoSuchFileException e) {
            realRoot = canonicalRoot;
        }
        Path parent = abs.getParent();
        if (parent == null) return;
        Path cur = canonicalRoot;
        for (Path component : canonicalRoot.relativize(parent)) {
            if (component.toString().isEmpty() || component.toString().equals(".")) continue;
            cur = cur.resolve(component.toString());
            if (!Files.exists(cur, LinkOption.NOFOLLOW_LINKS)) break;
            Path real;
            try {
                real = cur.toRealPath();
            } catch (IOException e) {
                break;
            }
            if (!real.startsWith(realRoot)) {
                throw new ChronoException.UnsafePathException(
                    "Path escape through symlink: " + cur + " resolves outside " + root);
            }
        }
    }

    public static boolean isWithin(Path candidate, Path root) {
        return candidate.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize());
    }
}