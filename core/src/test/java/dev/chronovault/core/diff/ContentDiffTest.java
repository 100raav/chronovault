package dev.chronovault.core.diff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContentDiffTest {

    @TempDir Path tempDir;

    @Test
    void producesLineHunksBetweenVersions() throws Exception {
        Path a = tempDir.resolve("a.txt");
        Path b = tempDir.resolve("b.txt");
        Files.writeString(a, "line1\nline2\nline3\nline4\n");
        Files.writeString(b, "line1\nline2-changed\nline3\nline4\nline5-new\n");

        ContentDiff.FileDiff diff = new ContentDiff().diff(a, b);

        assertTrue(diff.changed());
        assertFalse(diff.binary());
        boolean sawChange = diff.lines().stream().anyMatch(l -> l.kind() != ' ');
        assertTrue(sawChange);
        boolean sawContext = diff.lines().stream().anyMatch(l -> l.kind() == ' ');
        assertTrue(sawContext);
    }

    @Test
    void identicalFilesAreMarkedUnchanged() throws Exception {
        Path a = tempDir.resolve("a.txt");
        Path b = tempDir.resolve("b.txt");
        Files.writeString(a, "same\ncontent\n");
        Files.writeString(b, "same\ncontent\n");
        ContentDiff.FileDiff diff = new ContentDiff().diff(a, b);
        assertFalse(diff.changed());
    }

    @Test
    void binaryFilesAreDetected() throws Exception {
        Path a = tempDir.resolve("a.bin");
        Path b = tempDir.resolve("b.bin");
        Files.write(a, new byte[]{0x00, 0x01, 0x02});
        Files.write(b, new byte[]{0x00, 0x01, 0x03});
        ContentDiff.FileDiff diff = new ContentDiff().diff(a, b);
        assertTrue(diff.changed());
    }

    @Test
    void missingFilesYieldAllAddOrAllDelete() throws Exception {
        Path existing = tempDir.resolve("f.txt");
        Files.writeString(existing, "one\ntwo\n");
        ContentDiff.FileDiff added = new ContentDiff().diff(null, existing);
        assertTrue(added.changed());
        assertTrue(added.lines().stream().allMatch(l -> l.kind() == '+'));

        ContentDiff.FileDiff removed = new ContentDiff().diff(existing, null);
        assertTrue(removed.lines().stream().allMatch(l -> l.kind() == '-'));
    }
}