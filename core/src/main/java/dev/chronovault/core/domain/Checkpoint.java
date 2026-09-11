package dev.chronovault.core.domain;

import java.time.Instant;

public record Checkpoint(
    CheckpointId id,
    ProjectId projectId,
    SnapshotId snapshotId,
    Instant createdAt,
    String label,
    CheckpointStatus status,
    VerificationEvidence evidence,
    String gitBranch,
    String gitCommit,
    boolean pinned,
    boolean autoCreated
) {
    public boolean isVerified() {
        return status == CheckpointStatus.VERIFIED;
    }

    public String shortId() {
        return id.value().replace("cp-", "");
    }

    public Checkpoint withStatus(CheckpointStatus newStatus) {
        return new Checkpoint(id, projectId, snapshotId, createdAt, label, newStatus,
            evidence, gitBranch, gitCommit, pinned, autoCreated);
    }

    public Checkpoint withPinned(boolean newPinned) {
        return new Checkpoint(id, projectId, snapshotId, createdAt, label, status,
            evidence, gitBranch, gitCommit, newPinned, autoCreated);
    }
}