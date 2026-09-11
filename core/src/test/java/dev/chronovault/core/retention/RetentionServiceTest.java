package dev.chronovault.core.retention;

import dev.chronovault.core.application.ChronoVault;
import dev.chronovault.core.domain.*;
import dev.chronovault.core.recovery.TestVaultHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RetentionServiceTest {

    @TempDir Path tempDir;

    @Test
    void gcCollectsOrphanedObjects() throws Exception {
        Path root = tempDir.resolve("proj");
        Files.createDirectories(root);
        TestVaultHarness.write(root, "src/A.java", "AAAA\n");
        TestVaultHarness.write(root, "src/Deleted.java", "DELETE ME LATER\n");

        try (TestVaultHarness h = new TestVaultHarness(root, TestVaultHarness.alwaysPass("t"))) {
            ChronoVault vault = h.vault;
            SnapshotManifest s1 = vault.snapshotEngine().createSnapshot(vault.projectContext(), null);
            long objectsBefore = vault.contentStore().count();

            // New snapshot with Deleted.java gone → no new content objects are created
            // (deletion of a file produces no new hash); the object becomes unreferenced
            // only once the s1 metadata is removed below.
            Files.delete(root.resolve("src/Deleted.java"));
            SnapshotManifest s2 = vault.snapshotEngine().createIncremental(
                vault.projectContext(), s1, null);

            long objectsAfter2 = vault.contentStore().count();
            assertEquals(objectsBefore, objectsAfter2,
                "deleting a file alone must not create new objects");

            // Remove s1 from metadata so A/AAAA is still referenced but DELETE-ME is not.
            vault.metaStore().deleteSnapshot(vault.projectContext().projectId(), s1.snapshotId());

            RetentionService retention = new RetentionService(vault.metaStore(), vault.contentStore());
            RetentionService.RetentionReport report = retention.gc(vault.projectContext(), false);

            assertTrue(report.objectsRemoved() >= 1, "orphaned object must be collected");
            assertTrue(report.bytesFreed() > 0);

            // The still-referenced object must survive.
            assertTrue(vault.contentStore().contains(
                dev.chronovault.core.util.Hashing.hashString("AAAA\n")));
        }
    }

    @Test
    void retentionPolicyProtectsPinnedAndRecent() throws Exception {
        Path root = tempDir.resolve("proj");
        Files.createDirectories(root);
        TestVaultHarness.write(root, "src/X.java", "x\n");

        try (TestVaultHarness h = new TestVaultHarness(root, TestVaultHarness.alwaysPass("t"))) {
            ChronoVault vault = h.vault;
            Checkpoint c1 = vault.createCheckpoint("one", msg -> {});
            TestVaultHarness.write(root, "src/X.java", "xx\n");
            Checkpoint c2 = vault.createCheckpoint("two", msg -> {});
            vault.pinCheckpoint(c1.id());

            VaultConfig.RetentionPolicy policy = new VaultConfig.RetentionPolicy(1, 0, true, true);
            RetentionService retention = new RetentionService(vault.metaStore(), vault.contentStore());
            RetentionService.RetentionReport report = retention.applyPolicy(
                vault.projectContext(), policy, false);

            assertEquals(0, report.checkpointsRemoved(),
                "pinned + last healthy + latest must all be preserved");
            assertEquals(2, vault.checkpoints().size());
        }
    }
}