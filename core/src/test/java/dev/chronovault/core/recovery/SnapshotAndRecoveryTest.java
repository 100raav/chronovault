package dev.chronovault.core.recovery;

import dev.chronovault.core.application.ChronoVault;
import dev.chronovault.core.domain.*;
import dev.chronovault.core.project.ProjectContext;
import dev.chronovault.core.checkpoint.CheckpointService;
import dev.chronovault.core.health.HealthEngine;
import dev.chronovault.core.recovery.RecoveryGuard;
import dev.chronovault.core.recovery.RecoveryService;
import dev.chronovault.core.snapshot.DefaultSnapshotEngine;
import dev.chronovault.core.snapshot.SnapshotEngine;
import dev.chronovault.core.util.Hashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotAndRecoveryTest {

    @TempDir Path tempDir;

    @Test
    void endToEndRecoveryCycles() throws Exception {
        Path root = tempDir.resolve("proj");
        Files.createDirectories(root);
        TestVaultHarness.write(root, "src/PaymentService.java",
            "public class PaymentService { int calculate(int n) { return n * 2; } }\n");
        TestVaultHarness.write(root, "src/OrderService.java",
            "public class OrderService { String status() { return \"ok\"; } }\n");

        HealthProfile profile = TestVaultHarness.alwaysPass("test");
        try (TestVaultHarness h = new TestVaultHarness(root, profile)) {
            ChronoVault vault = h.vault;

            // First verified checkpoint.
            Checkpoint c1 = vault.createCheckpoint("baseline", msg -> {});
            assertTrue(c1.isVerified());
            assertEquals(1, vault.checkpoints().size());
            assertTrue(vault.checkpointService().findLastVerified(vault.projectContext()).isPresent());

            // Modify the project (build + tests previously passing; this is the "in development" phase).
            TestVaultHarness.write(root, "src/PaymentService.java",
                "public class PaymentService { int calculate(int n) { throw new RuntimeException(); } }\n");
            TestVaultHarness.write(root, "src/NewFeature.java", "public class NewFeature {}\n");
            Files.delete(root.resolve("src/OrderService.java"));

            // Project is now "broken" in the effective sense.
            HealthResult healthNow = vault.runHealth(msg -> {});
            assertTrue(healthNow.overallPass());  // echo-check still passes (real health unchanged)

            // Recovery: plan should produce actions.
            RecoveryService.RecoveryOutcome planned = vault.recoveryService().planRecovery(
                vault.projectContext(), c1.id(), (s, m) -> {});
            RestorePlan plan = planned.plan();
            assertTrue(plan.actions().size() >= 2);

            // Execute recovery.
            RecoveryService.RecoveryOutcome outcome = vault.recoveryService().executeRecovery(
                vault.projectContext(), planned, (s, m) -> {}, true);
            assertEquals(OperationStage.COMPLETED, outcome.finalStage());
            assertTrue(outcome.success());

            // Working tree must equal checkpoint c1 content exactly.
            String payment = Files.readString(root.resolve("src/PaymentService.java"));
            assertEquals("public class PaymentService { int calculate(int n) { return n * 2; } }\n", payment);
            assertTrue(Files.exists(root.resolve("src/OrderService.java")));
            assertFalse(Files.exists(root.resolve("src/NewFeature.java")));

            // Recovery must be journaled.
            List<RecoveryOperation> ops = vault.operationHistory();
            assertFalse(ops.isEmpty());
            RecoveryOperation last = ops.get(ops.size() - 1);
            assertEquals(OperationStage.COMPLETED, last.stage());
        }
    }

    @Test
    void entireFluentRecoveryThroughFacade() throws Exception {
        Path root = tempDir.resolve("proj2");
        Files.createDirectories(root);
        TestVaultHarness.write(root, "src/A.java", "A=1\n");
        TestVaultHarness.write(root, "src/B.java", "B=2\n");
        HealthProfile profile = TestVaultHarness.alwaysPass("t");
        try (TestVaultHarness h = new TestVaultHarness(root, profile)) {
            ChronoVault vault = h.vault;
            vault.createCheckpoint("v1", msg -> {});

            TestVaultHarness.write(root, "src/A.java", "A=999\n");
            TestVaultHarness.write(root, "src/C.java", "C=3\n");

            RecoveryService.RecoveryOutcome outcome = vault.returnToLastGood((s, m) -> {}, true);
            assertEquals(OperationStage.COMPLETED, outcome.finalStage());

            assertEquals("A=1\n", Files.readString(root.resolve("src/A.java")));
            assertFalse(Files.exists(root.resolve("src/C.java")));
            assertTrue(Files.exists(root.resolve("src/B.java")));
        }
    }

    @Test
    void selectiveRecoveryRestoresOnlyMatchingPaths() throws Exception {
        Path root = tempDir.resolve("proj3");
        Files.createDirectories(root);
        TestVaultHarness.write(root, "src/A.java", "A=1\n");
        TestVaultHarness.write(root, "src/B.java", "B=2\n");
        TestVaultHarness.write(root, "conf/x.yml", "x: 1\n");
        HealthProfile profile = TestVaultHarness.alwaysPass("t");
        try (TestVaultHarness h = new TestVaultHarness(root, profile)) {
            ChronoVault vault = h.vault;
            vault.createCheckpoint("v1", msg -> {});

            TestVaultHarness.write(root, "src/A.java", "A=999\n");
            TestVaultHarness.write(root, "src/B.java", "B=999\n");
            TestVaultHarness.write(root, "conf/x.yml", "x: 999\n");

            RecoveryService.RecoveryOutcome outcome = vault.executeRecovery(
                vault.checkpointService().findLastVerified(vault.projectContext()).orElseThrow().id(),
                p -> p.startsWith("src/") && p.contains("A.java"),
                (s, m) -> {}, true);
            assertEquals(OperationStage.COMPLETED, outcome.finalStage());

            assertEquals("A=1\n", Files.readString(root.resolve("src/A.java")), "selected file restored");
            assertEquals("B=999\n", Files.readString(root.resolve("src/B.java")), "unselected file untouched");
            assertEquals("x: 999\n", Files.readString(root.resolve("conf/x.yml")), "unselected file untouched");
        }
    }
}