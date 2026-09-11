package dev.chronovault.core.checkpoint;

import dev.chronovault.core.domain.*;
import dev.chronovault.core.project.FingerprintService;
import dev.chronovault.core.project.ProjectContext;
import dev.chronovault.core.snapshot.SnapshotEngine;
import dev.chronovault.core.storage.MetadataStore;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class CheckpointService {
    private final SnapshotEngine snapshotEngine;
    private final MetadataStore metadataStore;
    private final FingerprintService fingerprints;

    public CheckpointService(SnapshotEngine snapshotEngine, MetadataStore metadataStore,
                             FingerprintService fingerprints) {
        this.snapshotEngine = snapshotEngine;
        this.metadataStore = metadataStore;
        this.fingerprints = fingerprints;
    }

    public Checkpoint createCheckpoint(ProjectContext ctx, String label,
                                       boolean autoCreated,
                                       Consumer<String> progress) throws IOException {
        SnapshotManifest manifest = snapshotEngine.createSnapshot(ctx, progress);
        return createCheckpointFromManifest(ctx, manifest, label, null, autoCreated);
    }

    public Checkpoint createCheckpointFromManifest(ProjectContext ctx, SnapshotManifest manifest,
                                                   String label, HealthResult health,
                                                   boolean autoCreated) throws IOException {
        FingerprintService.GitState git = fingerprints.gitState(ctx.root());
        VerificationEvidence evidence = new VerificationEvidence(
            ctx.config().activeHealthProfile(),
            ctx.config().activeProfile().map(HealthProfile::version).orElse(1),
            health,
            fingerprints.toolchain(),
            fingerprints.dependencies(ctx.root(), fingerprints.detectManifestNames(ctx.root())),
            fingerprints.environment()
        );

        CheckpointStatus status = health != null && health.overallPass()
            ? CheckpointStatus.VERIFIED
            : (health != null ? CheckpointStatus.BROKEN : CheckpointStatus.UNVERIFIED);

        Checkpoint cp = new Checkpoint(
            CheckpointId.generate(),
            ctx.projectId(),
            manifest.snapshotId(),
            Instant.now(),
            label,
            status,
            evidence,
            git.branch,
            git.commit,
            false,
            autoCreated
        );
        metadataStore.saveCheckpoint(cp);
        return cp;
    }

    public Optional<Checkpoint> findLastVerified(ProjectContext ctx) {
        // Status is the authoritative flag (set after a real verification run);
        // evidence() may legitimately lack a health result in some creation paths.
        return metadataStore.listCheckpoints(ctx.projectId()).stream()
            .filter(cp -> cp.status() == CheckpointStatus.VERIFIED)
            .max(Comparator.comparing(Checkpoint::createdAt));
    }

    public Optional<Checkpoint> findLastVerifiedBefore(ProjectContext ctx, Instant before) {
        return metadataStore.listCheckpoints(ctx.projectId()).stream()
            .filter(cp -> cp.createdAt().isBefore(before))
            .filter(cp -> cp.status() == CheckpointStatus.VERIFIED)
            .max(Comparator.comparing(Checkpoint::createdAt));
    }

    public List<Checkpoint> list(ProjectContext ctx) {
        return metadataStore.listCheckpoints(ctx.projectId());
    }

    public List<Checkpoint> list(ProjectContext ctx, int limit) {
        return metadataStore.listCheckpoints(ctx.projectId(), limit);
    }

    public Optional<Checkpoint> get(ProjectContext ctx, CheckpointId id) {
        return metadataStore.getCheckpoint(ctx.projectId(), id);
    }

    public Checkpoint pin(ProjectContext ctx, CheckpointId id, boolean pinned) {
        metadataStore.setPinned(ctx.projectId(), id, pinned);
        return metadataStore.getCheckpoint(ctx.projectId(), id).orElseThrow();
    }

    public void setStatus(ProjectContext ctx, CheckpointId id, CheckpointStatus status) {
        metadataStore.setCheckpointStatus(ctx.projectId(), id, status);
    }
}