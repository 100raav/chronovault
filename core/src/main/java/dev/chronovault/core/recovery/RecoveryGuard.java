package dev.chronovault.core.recovery;

import dev.chronovault.core.domain.*;
import dev.chronovault.core.project.ProjectContext;
import dev.chronovault.core.snapshot.SnapshotEngine;
import dev.chronovault.core.storage.MetadataStore;
import dev.chronovault.core.storage.RecoveryJournal;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * On startup, reconciles operations that were interrupted by a crash.
 * If a restore was in progress (assets may be partially mutated), the journal's
 * protective snapshot is restored so the working tree returns to the pre-recovery state.
 */
public final class RecoveryGuard {
    private final SnapshotEngine snapshotEngine;
    private final MetadataStore metadataStore;
    private final RecoveryJournal journal;

    public RecoveryGuard(SnapshotEngine snapshotEngine, MetadataStore metadataStore,
                         RecoveryJournal journal) {
        this.snapshotEngine = snapshotEngine;
        this.metadataStore = metadataStore;
        this.journal = journal;
    }

    public record Reconciliation(List<String> recoveredOps, List<String> failures) {}

    private static final java.util.Set<OperationStage> IN_PROGRESS = java.util.Set.of(
        OperationStage.PLANNED, OperationStage.PROTECTING, OperationStage.PROTECTED,
        OperationStage.RESTORING, OperationStage.VERIFYING, OperationStage.COMMITTING,
        OperationStage.ROLLING_BACK);

    public Reconciliation reconcile(ProjectContext ctx) {
        List<String> recovered = new java.util.ArrayList<>();
        List<String> failures = new java.util.ArrayList<>();

        // Only operations whose *latest* journal entry is still in progress were
        // interrupted by a crash. Completed/rolled-back/cancelled operations keep
        // intermediate entries in the append-only journal and must be left alone.
        Map<String, RecoveryJournal.JournalEntry> latestPerOp = journal.snapshot().stream()
            .collect(Collectors.toMap(
                RecoveryJournal.JournalEntry::operationId,
                e -> e,
                (a, b) -> a.timestamp() >= b.timestamp() ? a : b
            ));

        for (RecoveryJournal.JournalEntry e : latestPerOp.values()) {
            if (!IN_PROGRESS.contains(e.stage())) continue;
            try {
                boolean mutated = switch (e.stage()) {
                    case RESTORING, VERIFYING, COMMITTING, ROLLING_BACK -> true;
                    default -> false;
                };
                if (mutated && e.protectiveSnapshot() != null) {
                    SnapshotManifest protective = metadataStore.getSnapshotManifest(
                        ctx.projectId(), SnapshotId.of(e.protectiveSnapshot())).orElse(null);
                    if (protective != null) {
                        snapshotEngine.restoreSnapshot(ctx, protective, false);
                        recovered.add(e.operationId());
                    }
                }
                RecoveryOperation done = new RecoveryOperation(
                    e.operationId(), ctx.projectId(),
                    ProtectiveOpStatus(e),
                    0, "Reconciled after crash — state returned to pre-recovery",
                    e.targetCheckpoint() != null ? CheckpointId.of(e.targetCheckpoint()) : null,
                    e.protectiveSnapshot() != null ? SnapshotId.of(e.protectiveSnapshot()) : null,
                    Instant.ofEpochMilli(e.timestamp()), Instant.now(),
                    "{\"reconciledAfterCrash\":true}");
                metadataStore.saveOperationState(done);
                journal.append(done);
            } catch (IOException ex) {
                failures.add(e.operationId() + ": " + ex.getMessage());
            }
        }
        return new Reconciliation(recovered, failures);
    }

    private OperationStage ProtectiveOpStatus(RecoveryJournal.JournalEntry e) {
        return switch (e.stage()) {
            case RESTORING, VERIFYING, COMMITTING, ROLLING_BACK -> OperationStage.ROLLED_BACK;
            default -> OperationStage.CANCELLED;
        };
    }
}