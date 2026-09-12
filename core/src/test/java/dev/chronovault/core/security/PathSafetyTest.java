package dev.chronovault.core.security;

import dev.chronovault.core.ChronoException;
import dev.chronovault.core.util.PathSafety;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PathSafetyTest {

    @Test
    void rejectsTraversalAttempts() {
        assertThrows(ChronoException.UnsafePathException.class,
            () -> PathSafety.resolveInside(Path.of("/proj"), "../etc/passwd"));
        assertThrows(ChronoException.UnsafePathException.class,
            () -> PathSafety.resolveInside(Path.of("/proj"), "a/../../../../etc/passwd"));
        assertThrows(ChronoException.UnsafePathException.class,
            () -> PathSafety.resolveInside(Path.of("/proj"), "..\\..\\Windows\\System32"));
        assertThrows(ChronoException.UnsafePathException.class,
            () -> PathSafety.resolveInside(Path.of("/proj"), "/absolute/path"));
        assertThrows(ChronoException.UnsafePathException.class,
            () -> PathSafety.resolveInside(Path.of("/proj"), ""));
        assertThrows(ChronoException.UnsafePathException.class,
            () -> PathSafety.resolveInside(Path.of("/proj"), null));
    }

    @Test
    void acceptsSafeRelativePaths() {
        assertDoesNotThrow(() -> PathSafety.resolveInside(Path.of("/proj"), "src/Main.java"));
        assertDoesNotThrow(() -> PathSafety.resolveInside(Path.of("/proj"), "a/b/c/d.txt"));
        assertDoesNotThrow(() -> PathSafety.resolveInside(Path.of("/proj"), "file with spaces.java"));
        assertDoesNotThrow(() -> PathSafety.resolveInside(Path.of("/proj"), "ünïcødé.txt"));
    }

    @Test
    void normalizedAndWithinChecks() {
        assertFalse(PathSafety.containsEscapes("safe/path.txt"));
        assertTrue(PathSafety.containsEscapes(".."));
        assertTrue(PathSafety.containsEscapes("a/../b"));
        assertTrue(PathSafety.isWithin(Path.of("/proj/src"), Path.of("/proj")));
        assertFalse(PathSafety.isWithin(Path.of("/other"), Path.of("/proj")));
    }

    @Test
    void windowsStyleBackslashTraversalIsCaught() {
        assertTrue(PathSafety.containsEscapes("..\\evil"));
        assertTrue(PathSafety.containsEscapes("src\\..\\..\\escape"));
    }

    @Test
    void projectRootItselfIsValidDirectoryTarget(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.resolve("project-root");
        Files.createDirectories(root);

        assertDoesNotThrow(() -> PathSafety.validateDirectory(root, root));
    }

    @Test
    void symlinkedAncestorEscapingRootIsRejected() throws Exception {
        Path root = Files.createTempDirectory("cv-ps-root");
        Path outside = Files.createTempDirectory("cv-ps-outside");
        Files.createDirectories(root.resolve("a"));
        assertDoesNotThrow(() -> PathSafety.validateWritePath(root.resolve("a/x.txt"), root));
        try {
            Files.move(root.resolve("a"), root.resolve("a-orig"));
            Files.createSymbolicLink(root.resolve("a"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // filesystem without symlink support
        }
        Files.createDirectories(outside.resolve("x"));
        assertThrows(ChronoException.UnsafePathException.class,
            () -> PathSafety.validateWritePath(root.resolve("a/x/evil.txt"), root));
        assertDoesNotThrow(() -> PathSafety.validateWritePath(root.resolve("a-orig/x.txt"), root));
    }

    @Test
    void symlinkInsideRootIsAllowed() throws Exception {
        Path root = Files.createTempDirectory("cv-ps-in");
        Path real = Files.createTempDirectory("cv-ps-real");
        Files.createDirectories(root.resolve("real-dir"));
        try {
            Files.move(root.resolve("real-dir"), root.resolve("real-dir-actual"));
            Files.createSymbolicLink(root.resolve("real-dir"), root.resolve("real-dir-actual"));
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        assertDoesNotThrow(() -> PathSafety.validateWritePath(root.resolve("real-dir/file.txt"), root));
    }
}