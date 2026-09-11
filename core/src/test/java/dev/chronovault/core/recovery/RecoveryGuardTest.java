package dev.chronovault.core.recovery;

import dev.chronovault.core.domain.Checkpoint;
import dev.chronovault.core.domain.CheckpointStatus;
import dev.chronovault.core.domain.OperationStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression: RecoveryGuard.reconcile runs on every vault open. It must only reconcile
 * operations whose *latest* journal entry is still in progress. A successfully completed
 * recovery keeps intermediate PLANNED/RESTORING/VERIFYING entries in the append-only
 * journal and must NOT be rolled back — neither in history nor on disk.
 */
class RecoveryGuardTest {

    @TempDir Path tempDir;

    @Test
    void completedRecoverySurvivesVaultReopen() throws Exception {
        Path root = tempDir.resolve("proj");
        Files.createDirectories(root);
        TestVaultHarness.write(root, "src/App.java", "GOOD v1\n");
        var profile = TestVaultHarness.alwaysPass("t");
        final String[] opId = new String[1];

        try (TestVaultHarness h = new TestVaultHarness(root, profile)) {
            Checkpoint cp = h.vault.createCheckpoint("base", msg -> {});
            h.vault.checkpointService().setStatus(h.vault.projectContext(), cp.id(), CheckpointStatus.VERIFIED);

            TestVaultHarness.write(root, "src/App.java", "BROKEN v2\n");

            var outcome = h.vault.executeRecovery(cp.id(), (s, m) -> {}, true);
            assertEquals(OperationStage.COMPLETED, outcome.finalStage(), "restore must commit when verification passes");
            assertEquals("GOOD v1\n", Files.readString(root.resolve("src/App.java")));
            opId[0] = outcome.operationId().value();

            var completed = h.vault.operationHistory().stream()
                .filter(o -> o.operationId().equals(opId[0])).findFirst().orElseThrow();
            assertEquals(OperationStage.COMPLETED, completed.stage());
        }

        try (TestVaultHarness reopened = new TestVaultHarness(root, profile)) {
            var op = reopened.vault.operationHistory().stream()
                .filter(o -> o.operationId().equals(opId[0])).findFirst().orElseThrow();
            assertEquals(OperationStage.COMPLETED, op.stage(),
                "reopen-reconcile must not flip a completed recovery to ROLLED_BACK");
            assertEquals("GOOD v1\n", Files.readString(root.resolve("src/App.java")),
                "reopen-reconcile must not restore a protective snapshot over a finished recovery");
            assertEquals(1, Files.list(root.resolve("src")).count());
        }
    }

    @Test
    void unchangedPlanIsCancelledWithoutTouchingFiles() throws Exception {
        Path root = tempDir.resolve("proj2");
        Files.createDirectories(root);
        TestVaultHarness.write(root, "src/App.java", "payload\n");
        var profile = TestVaultHarness.alwaysPass("t");
        final String[] planOpId = new String[1];

        try (TestVaultHarness h = new TestVaultHarness(root, profile)) {
            Checkpoint cp = h.vault.createCheckpoint("base", msg -> {});
            h.vault.checkpointService().setStatus(h.vault.projectContext(), cp.id(), CheckpointStatus.VERIFIED);

            // Planning alone writes a PLANNED journal entry and never mutates files.
            var planned = h.vault.planRecovery(cp.id(), (s, m) -> {});
            assertEquals(OperationStage.PLANNED, planned.finalStage());
            planOpId[0] = planned.operationId().value();
        }

        // Reopen triggers RecoveryGuard.reconcile, which must cancel the abandoned plan.
        try (TestVaultHarness reopened = new TestVaultHarness(root, profile)) {
            assertEquals("payload\n", Files.readString(root.resolve("src/App.java")),
                "a mere plan must never modify the working tree");
            var op = reopened.vault.operationHistory().stream()
                .filter(o -> o.operationId().equals(planOpId[0])).findFirst().orElseThrow();
            assertEquals(OperationStage.CANCELLED, op.stage(), "abandoned plan should be cancelled, not rolled back");
        }
    }
}