package dev.chronovault.core.recovery;

import dev.chronovault.core.application.ChronoVault;
import dev.chronovault.core.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mandatory rollback test: healthy → modify → break → protect → restore →
 * force verification failure → rollback → verify original (protected) state.
 */
class RollbackTest {

    @TempDir Path tempDir;

    @Test
    void rollbackRestoresPreRecoveryStateWhenVerificationFails() throws Exception {
        Path root = tempDir.resolve("project");
        Files.createDirectories(root);
        TestVaultHarness.write(root, "src/Main.java", "v 1\n");
        TestVaultHarness.write(root, "src/Other.java", "other\n");

        HealthProfile pass = TestVaultHarness.alwaysPass("pass");
        try (TestVaultHarness h = new TestVaultHarness(root, pass)) {
            ChronoVault vault = h.vault;
            Checkpoint good = vault.createCheckpoint("good", msg -> {});
            assertTrue(good.isVerified());

            // Break the state.
            Files.delete(root.resolve("src/Other.java"));
            TestVaultHarness.write(root, "src/Main.java", "v 2 — broken\n");

            // Open the same vault with a FAILING profile to simulate a post-restore verification failure.
            try (TestVaultHarness h2 = new TestVaultHarness(root,
                    TestVaultHarness.runJs("fail", "process.exit(1)"))) {
                ChronoVault vault2 = h2.vault;
                assertFalse(vault2.runHealth(m -> {}).overallPass(), "sanity: failing profile fails");

                Checkpoint target = vault2.checkpointService().get(vault2.projectContext(), good.id())
                    .orElseThrow();

                RecoveryService.RecoveryOutcome planned = vault2.recoveryService().planRecovery(
                    vault2.projectContext(), target.id(), (s, m) -> {});
                RecoveryService.RecoveryOutcome outcome = vault2.recoveryService().executeRecovery(
                    vault2.projectContext(), planned, (s, m) -> {}, true);

                assertEquals(OperationStage.ROLLED_BACK, outcome.finalStage());
                assertFalse(outcome.success(), "recovery that fails verification must not report success");

                // The protected pre-recovery state must be restored byte-for-byte.
                assertEquals("v 2 — broken\n", Files.readString(root.resolve("src/Main.java")));
                assertFalse(Files.exists(root.resolve("src/Other.java")),
                    "rollback restores the pre-recovery state exactly");

                // Journal must reflect the rollback path.
                List<RecoveryOperation> ops = vault2.operationHistory();
                RecoveryOperation last = ops.get(0);
                assertEquals(OperationStage.ROLLED_BACK, last.stage());
            }
        }
    }
}