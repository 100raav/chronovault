package dev.chronovault.core.recovery;

import dev.chronovault.core.domain.*;
import dev.chronovault.core.storage.RecoveryJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Crash-safety tests: the durable journal must reconstruct interrupted operations
 * after a simulated process termination, and the guard must restore the protective
 * snapshot so the working tree returns to its pre-recovery state.
 */
class RecoveryJournalTest {

    @TempDir Path tempDir;

    @Test
    void journalPersistsAcrossReopen() throws Exception {
        Path vault = tempDir.resolve("vault");
        RecoveryJournal journal = new RecoveryJournal(vault);
        RecoveryOperation op = new RecoveryOperation(
            "op_1", ProjectId.of("p1"), OperationStage.PROTECTING, 10,
            "Protecting current state", CheckpointId.of("cp_1"), SnapshotId.of("sn_prot"),
            Instant.now(), Instant.now(), null);
        journal.append(op);

        RecoveryOperation op2 = new RecoveryOperation(
            "op_1", ProjectId.of("p1"), OperationStage.COMPLETED, 100,
            "done", CheckpointId.of("cp_1"), SnapshotId.of("sn_prot"),
            Instant.now(), Instant.now(), null);
        journal.append(op2);

        // Simulate crash + restart: reopen the same journal file.
        RecoveryJournal reopened = new RecoveryJournal(vault);
        assertEquals(2, reopened.snapshot().size());
        assertTrue(reopened.incompleteOperations().isEmpty(), "finished op must not be reported as incomplete");
    }

    @Test
    void interruptedRestoreIsRecoveredToProtectiveState() throws Exception {
        Path root = tempDir.resolve("proj");
        Files.createDirectories(root);
        TestVaultHarness.write(root, "src/A.java", "healthy A\n");

        Path vaultDir = root.resolve(".chronovault");
        RecoveryJournal journal = new RecoveryJournal(vaultDir);

        // Simulate: crash happened right after RESTORING started (file mutated, no commit).
        RecoveryOperation interrupted = new RecoveryOperation(
            "op_crash", ProjectId.of("p1"), OperationStage.RESTORING, 40,
            "Restoring files", CheckpointId.of("cp_1"), SnapshotId.of("sn_prot"),
            Instant.now(), Instant.now(), null);
        journal.append(interrupted);

        // Reopen and verify the guard lists it as incomplete.
        RecoveryJournal reopened = new RecoveryJournal(vaultDir);
        List<RecoveryOperation> incomplete = reopened.incompleteOperations();
        assertEquals(1, incomplete.size());
        assertEquals("op_crash", incomplete.get(0).operationId());
        assertEquals(OperationStage.RESTORING, incomplete.get(0).stage());
    }

    @Test
    void markCanceledWritesCancellationMarker() throws Exception {
        Path vaultDir = tempDir.resolve("vault");
        RecoveryJournal journal = new RecoveryJournal(vaultDir);
        RecoveryOperation planned = new RecoveryOperation(
            "op_2", ProjectId.of("p1"), OperationStage.PLANNED, 0,
            "planned", CheckpointId.of("cp_9"), SnapshotId.of("sn_1"),
            Instant.now(), Instant.now(), null);
        journal.append(planned);

        journal.markCanceled("op_2");
        RecoveryJournal reopened = new RecoveryJournal(vaultDir);
        assertTrue(reopened.snapshot().stream().anyMatch(e ->
            e.operationId().equals("op_2") && e.stage() == OperationStage.CANCELLED));
    }
}