package dev.chronovault.core.recovery;

import dev.chronovault.core.domain.*;
import dev.chronovault.core.diff.DiffEngine;
import dev.chronovault.core.health.HealthEngine;
import dev.chronovault.core.project.ProjectContext;
import dev.chronovault.core.snapshot.SnapshotEngine;
import dev.chronovault.core.storage.ContentStore;
import dev.chronovault.core.storage.MetadataStore;
import dev.chronovault.core.storage.RecoveryJournal;
import dev.chronovault.core.util.PathSafety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

public final class RecoveryService {
    private final SnapshotEngine snapshotEngine;
    private final MetadataStore metadataStore;
    private final ContentStore contentStore;
    private final HealthEngine healthEngine;
    private final RecoveryJournal journal;
    private final DiffEngine diffEngine;

    public RecoveryService(SnapshotEngine snapshotEngine, MetadataStore metadataStore,
                           ContentStore contentStore, HealthEngine healthEngine,
                           RecoveryJournal journal, DiffEngine diffEngine) {
        this.snapshotEngine = snapshotEngine;
        this.metadataStore = metadataStore;
        this.contentStore = contentStore;
        this.healthEngine = healthEngine;
        this.journal = journal;
        this.diffEngine = diffEngine;
    }

    public record RecoveryOutcome(
        OperationId operationId,
        OperationStage finalStage,
        boolean success,
        String message,
        RestorePlan plan
    ) {}

    /**
     * Begin a recovery operation: creates a protective snapshot of the current state,
     * calculates the restore plan, and records the operation durably. Does not modify
     * project files yet.
     */
    public RecoveryOutcome planRecovery(ProjectContext ctx, CheckpointId targetCheckpointId,
                                        BiConsumer<OperationStage, String> progress) throws IOException {
        OperationId opId = OperationId.generate();
        Checkpoint target = metadataStore.getCheckpoint(ctx.projectId(), targetCheckpointId)
            .orElseThrow(() -> new IllegalArgumentException("Checkpoint not found: " + targetCheckpointId));
        if (target.status() != CheckpointStatus.VERIFIED && target.status() != CheckpointStatus.ACTIVE) {
            throw new IllegalArgumentException("Recovery target is not a verified checkpoint: " + targetCheckpointId);
        }

        progress.accept(OperationStage.PLANNED, "Planning recovery to " + targetCheckpointId);

        // PROTECTING: snapshot current working tree.
        SnapshotManifest protective = snapshotEngine.createSnapshot(ctx, msg ->
            progress.accept(OperationStage.PROTECTING, msg));
        progress.accept(OperationStage.PROTECTED, "Current state protected");

        SnapshotManifest current = metadataStore.getLatestSnapshot(ctx.projectId())
            .orElse(protective);
        SnapshotManifest targetManifest = metadataStore.getSnapshotManifest(ctx.projectId(), target.snapshotId())
            .orElseThrow(() -> new IOException("Target snapshot manifest missing: " + target.snapshotId()));

        DiffEngine.DiffResult diff = diffEngine.diff(
            current == null ? List.of() : current.entries(),
            targetManifest.entries());

        RestorePlan plan = new RestorePlan(
            targetCheckpointId,
            current != null ? current.snapshotId() : null,
            targetManifest.snapshotId(),
            ctx.projectId(),
            diff.actions(),
            diff.modified(),
            diff.added(),
            diff.deleted(),
            diff.renamed(),
            diff.actions().stream().mapToLong(a -> switch (a) {
                case RestoreAction.PutFile pf -> pf.size();
                default -> 0;
            }).sum(),
            protective.snapshotId(),
            List.of()
        );

        RecoveryOperation op = new RecoveryOperation(
            opId.value(), ctx.projectId(), OperationStage.PLANNED, 0,
            "Planned recovery to " + targetCheckpointId,
            targetCheckpointId, protective.snapshotId(), Instant.now(), Instant.now(), null);
        metadataStore.saveOperationState(op);
        journal.append(op);

        return new RecoveryOutcome(opId, OperationStage.PLANNED, false, "Planned", plan);
    }

    /**
     * Selective recovery: plan to {@code target}, keep only the restore actions whose
     * path matches {@code pathFilter}, then execute. Planning still protects the current
     * state, so the excluded files are untouched yet the whole operation remains rollback-safe.
     */
    public RecoveryOutcome executeRecovery(ProjectContext ctx, CheckpointId targetCheckpointId,
                                           java.util.function.Predicate<String> pathFilter,
                                           BiConsumer<OperationStage, String> progress,
                                           boolean verifyAfterRestore) throws IOException {
        RecoveryOutcome planned = planRecovery(ctx, targetCheckpointId, progress);
        RestorePlan plan = RestorePlan.filtered(planned.plan(), pathFilter);
        if (plan.actions().isEmpty()) {
            OperationId opId = planned.operationId();
            progress.accept(OperationStage.CANCELLED, "No files matched the selection — nothing to restore");
            RecoveryOperation cancelled = new RecoveryOperation(
                opId.value(), ctx.projectId(), OperationStage.CANCELLED, 0,
                "No files matched the selection", targetCheckpointId, plan.protectiveSnapshotId(),
                Instant.now(), Instant.now(), null);
            metadataStore.saveOperationState(cancelled);
            journal.append(cancelled);
            return new RecoveryOutcome(opId, OperationStage.CANCELLED, false,
                "No files matched the selection", planned.plan());
        }
        progress.accept(OperationStage.PLANNED,
            "Selective recovery to " + targetCheckpointId.value() + " — " + plan.actions().size() + " file(s)");
        return executeRecovery(ctx,
            new RecoveryOutcome(planned.operationId(), planned.finalStage(), planned.success(), planned.message(), plan),
            progress, verifyAfterRestore);
    }

    /**
     * Execute a previously planned recovery. Drives RESTORING → VERIFYING → COMMITTING → COMPLETED,
     * or ROLLING_BACK → ROLLED_BACK on verification failure. Returns when the operation completes.
     */
    public RecoveryOutcome executeRecovery(ProjectContext ctx, RecoveryOutcome planned,
                                           BiConsumer<OperationStage, String> progress,
                                           boolean verifyAfterRestore) throws IOException {
        OperationId opId = planned.operationId();
        RestorePlan plan = planned.plan();

        RecoveryOperation restoring = new RecoveryOperation(
            opId.value(), ctx.projectId(), OperationStage.RESTORING, 25,
            "Restoring files", plan.targetCheckpointId(), plan.protectiveSnapshotId(),
            Instant.now(), Instant.now(), null);
        metadataStore.saveOperationState(restoring);
        journal.append(restoring);
        progress.accept(OperationStage.RESTORING, "Restoring files");

        try {
            for (RestoreAction action : plan.actions()) {
                applyAction(ctx, action);
            }
        } catch (IOException e) {
            progress.accept(OperationStage.FAILED, "Restore failed: " + e.getMessage());
            String rollbackNote = "";
            try {
                SnapshotManifest protective = metadataStore.getSnapshotManifest(
                    ctx.projectId(), plan.protectiveSnapshotId()).orElse(null);
                if (protective != null) {
                    snapshotEngine.restoreSnapshot(ctx, protective, true);
                    rollbackNote = " Pre-recovery state restored.";
                }
            } catch (IOException re) {
                rollbackNote = " ROLLBACK FAILED — manual intervention required: " + re.getMessage();
                RecoveryOperation critical = new RecoveryOperation(
                    opId.value(), ctx.projectId(), OperationStage.FAILED, 50,
                    "Restore failed; rollback also failed", plan.targetCheckpointId(), plan.protectiveSnapshotId(),
                    Instant.now(), Instant.now(), null);
                metadataStore.saveOperationState(critical);
                journal.append(critical);
                throw new IOException("Restore failed: " + e.getMessage() + rollbackNote, e);
            }
            RecoveryOperation failed = new RecoveryOperation(
                opId.value(), ctx.projectId(), OperationStage.FAILED, 50,
                "Restore failed" + rollbackNote, plan.targetCheckpointId(), plan.protectiveSnapshotId(),
                Instant.now(), Instant.now(), null);
            metadataStore.saveOperationState(failed);
            journal.append(failed);
            return new RecoveryOutcome(opId, OperationStage.FAILED, false,
                e.getMessage() + rollbackNote, plan);
        }

        if (!verifyAfterRestore) {
            commitRecovery(ctx, opId, plan, progress);
            return new RecoveryOutcome(opId, OperationStage.COMPLETED, true, "State restored", plan);
        }

        progress.accept(OperationStage.VERIFYING, "Verifying restored state");
        RecoveryOperation verifying = new RecoveryOperation(
            opId.value(), ctx.projectId(), OperationStage.VERIFYING, 60,
            "Verifying restored state", plan.targetCheckpointId(), plan.protectiveSnapshotId(),
            Instant.now(), Instant.now(), null);
        metadataStore.saveOperationState(verifying);
        journal.append(verifying);

        HealthProfile profile = ctx.config().activeProfile()
            .orElseThrow(() -> new IOException("No active health profile configured"));
        HealthResult health = healthEngine.run(ctx, profile,
            msg -> progress.accept(OperationStage.VERIFYING, msg));

        if (health.overallPass()) {
            commitRecovery(ctx, opId, plan, progress);
            return new RecoveryOutcome(opId, OperationStage.COMPLETED, true,
                "State restored and verified", plan);
        }

        // Verification failed → rollback.
        return rollbackRecovery(ctx, opId, plan, health, progress);
    }

    private void commitRecovery(ProjectContext ctx, OperationId opId, RestorePlan plan,
                                BiConsumer<OperationStage, String> progress) throws IOException {
        progress.accept(OperationStage.COMMITTING, "Recording recovered state");
        RecoveryOperation committing = new RecoveryOperation(
            opId.value(), ctx.projectId(), OperationStage.COMMITTING, 85,
            "Recording recovered state", plan.targetCheckpointId(), plan.protectiveSnapshotId(),
            Instant.now(), Instant.now(), null);
        metadataStore.saveOperationState(committing);
        journal.append(committing);

        metadataStore.setCheckpointStatus(ctx.projectId(), plan.targetCheckpointId(), CheckpointStatus.VERIFIED);

        RecoveryOperation done = new RecoveryOperation(
            opId.value(), ctx.projectId(), OperationStage.COMPLETED, 100,
            "Recovery complete", plan.targetCheckpointId(), plan.protectiveSnapshotId(),
            Instant.now(), Instant.now(), null);
        metadataStore.saveOperationState(done);
        journal.append(done);
        progress.accept(OperationStage.COMPLETED, "Recovery complete");
    }

    private RecoveryOutcome rollbackRecovery(ProjectContext ctx, OperationId opId, RestorePlan plan,
                                             HealthResult health,
                                             BiConsumer<OperationStage, String> progress) throws IOException {
        progress.accept(OperationStage.ROLLING_BACK, "Verification failed — rolling back");
        RecoveryOperation rolling = new RecoveryOperation(
            opId.value(), ctx.projectId(), OperationStage.ROLLING_BACK, 75,
            "Rolling back to pre-recovery state", plan.targetCheckpointId(), plan.protectiveSnapshotId(),
            Instant.now(), Instant.now(), null);
        metadataStore.saveOperationState(rolling);
        journal.append(rolling);

        SnapshotManifest protective = metadataStore.getSnapshotManifest(ctx.projectId(), plan.protectiveSnapshotId())
            .orElseThrow(() -> new IOException("Protective snapshot missing: " + plan.protectiveSnapshotId()));

        try {
            snapshotEngine.restoreSnapshot(ctx, protective, true);
        } catch (IOException e) {
            RecoveryOperation critical = new RecoveryOperation(
                opId.value(), ctx.projectId(), OperationStage.FAILED, 75,
                "Rollback failed: " + e.getMessage(), plan.targetCheckpointId(), plan.protectiveSnapshotId(),
                Instant.now(), Instant.now(), null);
            metadataStore.saveOperationState(critical);
            journal.append(critical);
            throw new IOException("Rollback failed — manual intervention required: " + e.getMessage(), e);
        }

        RecoveryOperation rolledBack = new RecoveryOperation(
            opId.value(), ctx.projectId(), OperationStage.ROLLED_BACK, 90,
            "Restored pre-recovery state", plan.targetCheckpointId(), plan.protectiveSnapshotId(),
            Instant.now(), Instant.now(), "{\"verificationFailed\":true}");
        metadataStore.saveOperationState(rolledBack);
        journal.append(rolledBack);
        progress.accept(OperationStage.ROLLED_BACK, "Pre-recovery state restored");
        return new RecoveryOutcome(opId, OperationStage.ROLLED_BACK, false,
            "Verification failed and state rolled back", plan);
    }

    private void applyAction(ProjectContext ctx, RestoreAction action) throws IOException {
        Path root = ctx.root();
        switch (action) {
            case RestoreAction.PutFile pf -> {
                Path target = PathSafety.resolveInside(root, pf.path());
                PathSafety.validateDirectory(target.getParent(), root);
                if (Files.isSymbolicLink(target)) Files.deleteIfExists(target);
                Optional<Path> materialized = contentStore.materialize(pf.contentHash(), target);
                if (materialized.isEmpty()) {
                    throw new IOException("Content object missing in vault: " + pf.contentHash());
                }
            }
            case RestoreAction.PutSymlink ps -> {
                Path target = PathSafety.resolveInside(root, ps.path());
                PathSafety.validateDirectory(target.getParent(), root);
                Files.deleteIfExists(target);
                Files.createSymbolicLink(target, Path.of(ps.target()));
            }
            case RestoreAction.Remove rm -> {
                Path target = PathSafety.resolveInside(root, rm.path());
                if (!PathSafety.isWithin(target, root)) {
                    throw new dev.chronovault.core.ChronoException.UnsafePathException(
                        "Refusing to remove path outside project: " + rm.path());
                }
                Files.deleteIfExists(target);
            }
        }
    }
}