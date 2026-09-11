package dev.chronovault.core.snapshot;

import dev.chronovault.core.application.ChronoVault;
import dev.chronovault.core.domain.*;
import dev.chronovault.core.recovery.TestVaultHarness;
import dev.chronovault.core.util.Hashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotEngineTest {

    @TempDir Path tempDir;

    @Test
    void snapshotCapturesAndRestoresWholeTree() throws Exception {
        Path root = tempDir.resolve("proj");
        Files.createDirectories(root);
        TestVaultHarness.write(root, "src/Main.java", "class Main {}\n");
        TestVaultHarness.write(root, "src/deep/nested/Util.java", "class Util {}\n");
        TestVaultHarness.write(root, "README.md", "# project\n");
        TestVaultHarness.write(root, "conf/app.yml", "a: 1\n");

        try (TestVaultHarness h = new TestVaultHarness(root, TestVaultHarness.alwaysPass("t"))) {
            ChronoVault vault = h.vault;
            SnapshotManifest m = vault.snapshotEngine().createSnapshot(vault.projectContext(), null);

            assertTrue(m.fileCount() >= 4);
            Optional<ManifestEntry> mainEntry = m.entries().stream()
                .filter(e -> e.path().equals("src/Main.java")).findFirst();
            assertTrue(mainEntry.isPresent());
            assertEquals(Hashing.hashString("class Main {}\n"), mainEntry.get().contentHash());
            assertTrue(mainEntry.get().isRegularFile());

            // Mutate the working tree, then restore.
            Files.writeString(root.resolve("src/Main.java"), "class Main { int x; }\n");
            Files.delete(root.resolve("README.md"));
            TestVaultHarness.write(root, "extra.txt", "unexpected\n");
            TestVaultHarness.write(root, "src/deep/extra2.txt", "also extra\n");

            long restored = vault.snapshotEngine().restoreSnapshot(vault.projectContext(), m, true);

            assertEquals("class Main {}\n", Files.readString(root.resolve("src/Main.java")));
            assertTrue(Files.exists(root.resolve("README.md")));
            assertFalse(Files.exists(root.resolve("extra.txt")));
            assertFalse(Files.exists(root.resolve("src/deep/extra2.txt")));
            assertEquals(6, restored);  // 4 manifest files materialized + 2 extra files pruned
            assertTrue(Files.exists(root.resolve("src/deep/nested/Util.java")));
        }
    }

    @Test
    void ignorePatternsExcludeBuildArtifacts() throws Exception {
        Path root = tempDir.resolve("proj");
        Files.createDirectories(root);
        TestVaultHarness.write(root, "src/App.java", "class App {}\n");
        TestVaultHarness.write(root, "build/classes/App.class", "binary-garbage");
        TestVaultHarness.write(root, "node_modules/pkg/file.js", "noise");

        try (TestVaultHarness h = new TestVaultHarness(root, TestVaultHarness.alwaysPass("t"))) {
            ChronoVault vault = h.vault;
            SnapshotManifest m = vault.snapshotEngine().createSnapshot(vault.projectContext(), null);
            assertTrue(m.entries().stream().noneMatch(e -> e.path().startsWith("build/")),
                "build/ must be ignored");
            assertTrue(m.entries().stream().noneMatch(e -> e.path().startsWith("node_modules/")),
                "node_modules/ must be ignored");
            assertTrue(m.entries().stream().anyMatch(e -> e.path().equals("src/App.java")));
        }
    }

    @Test
    void physicalDeduplicationAcrossSnapshots() throws Exception {
        Path root = tempDir.resolve("dedup");
        Files.createDirectories(root);
        TestVaultHarness.write(root, "src/Stable.java", "unchanging content ------------------------\n");
        TestVaultHarness.write(root, "src/Changing.java", "v1\n");

        try (TestVaultHarness h = new TestVaultHarness(root, TestVaultHarness.alwaysPass("t"))) {
            ChronoVault vault = h.vault;
            SnapshotManifest s1 = vault.snapshotEngine().createSnapshot(vault.projectContext(), null);
            long objectsAfterFirst = vault.contentStore().count();

            TestVaultHarness.write(root, "src/Changing.java", "v TWO\n");
            SnapshotManifest s2 = vault.snapshotEngine().createIncremental(
                vault.projectContext(), s1, null);
            long objectsAfterSecond = vault.contentStore().count();

            assertEquals(SnapshotManifest.SnapshotType.INCREMENTAL, s2.type());
            assertNotNull(s2.parentSnapshotId());
            // Only one new object for the changed file.
            assertEquals(objectsAfterFirst + 1, objectsAfterSecond,
                "unchanged content must be deduplicated");
        }
    }

    @Test
    void roundTripPreservesBinaryFiles() throws Exception {
        Path root = tempDir.resolve("bin");
        Files.createDirectories(root);
        byte[] image = new byte[65536];
        new java.util.Random(42).nextBytes(image);
        Path img = root.resolve("assets/logo.bin");
        Files.createDirectories(img.getParent());
        Files.write(img, image);

        try (TestVaultHarness h = new TestVaultHarness(root, TestVaultHarness.alwaysPass("t"))) {
            ChronoVault vault = h.vault;
            SnapshotManifest m = vault.snapshotEngine().createSnapshot(vault.projectContext(), null);
            Files.write(img, new byte[]{0, 1, 2, 3});
            vault.snapshotEngine().restoreSnapshot(vault.projectContext(), m, true);
            assertArrayEquals(image, Files.readAllBytes(img));
        }
    }
}