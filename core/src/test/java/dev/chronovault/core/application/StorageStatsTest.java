package dev.chronovault.core.application;

import dev.chronovault.core.domain.SnapshotManifest;
import dev.chronovault.core.recovery.TestVaultHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StorageStatsTest {

    @TempDir Path tempDir;

    @Test
    void logicalStorageBytesComesFromSnapshotManifests() throws Exception {
        Path root = tempDir.resolve("proj");
        Files.createDirectories(root);
        TestVaultHarness.write(root, "src/A.txt", "a".repeat(512));
        TestVaultHarness.write(root, "src/B.txt", "b".repeat(256));

        try (TestVaultHarness h = new TestVaultHarness(root, TestVaultHarness.alwaysPass("t"))) {
            ChronoVault vault = h.vault;

            SnapshotManifest s1 = vault.snapshotEngine().createSnapshot(vault.projectContext(), null);
            TestVaultHarness.write(root, "src/A.txt", "a".repeat(1024));
            SnapshotManifest s2 = vault.snapshotEngine().createIncremental(
                vault.projectContext(), s1, null);

            long sumManifests = vault.metaStore().listSnapshots(vault.projectContext().projectId()).stream()
                .mapToLong(SnapshotManifest::totalLogicalBytes).sum();

            assertEquals(sumManifests, vault.metaStore().logicalStorageBytes(),
                "logical bytes must be the sum of manifest totalLogicalBytes");
            assertTrue(vault.metaStore().logicalStorageBytes() > 0,
                "logical bytes must not be a hard-coded zero");

            var stats = vault.storageStats();
            assertTrue(stats.logicalBytes() > stats.physicalBytes(),
                "dedup must make logical bytes exceed unique physical bytes (B.txt reused across snapshots)");
        }
    }

    @Test
    void logicalStorageBytesTracksDeletedSnapshots() throws Exception {
        Path root = tempDir.resolve("proj");
        Files.createDirectories(root);
        TestVaultHarness.write(root, "src/A.txt", "aaaa\n");

        try (TestVaultHarness h = new TestVaultHarness(root, TestVaultHarness.alwaysPass("t"))) {
            ChronoVault vault = h.vault;

            SnapshotManifest s1 = vault.snapshotEngine().createSnapshot(vault.projectContext(), null);
            TestVaultHarness.write(root, "src/A.txt", "bbbb\n");
            SnapshotManifest s2 = vault.snapshotEngine().createIncremental(
                vault.projectContext(), s1, null);
            long both = vault.metaStore().logicalStorageBytes();

            vault.metaStore().deleteSnapshot(vault.projectContext().projectId(), s1.snapshotId());
            long remaining = vault.metaStore().logicalStorageBytes();

            assertEquals(s2.totalLogicalBytes(), remaining,
                "after deleting s1 the sum must reflect only the surviving manifest");
            assertTrue(both > remaining, "the two-snapshot sum must exceed the single-snapshot sum");
        }
    }
}