package dev.chronovault.core.security;

import dev.chronovault.core.ChronoException;
import dev.chronovault.core.util.PathSafety;
import org.junit.jupiter.api.Test;

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
}