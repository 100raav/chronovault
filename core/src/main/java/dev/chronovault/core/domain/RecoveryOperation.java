package dev.chronovault.core.domain;

import java.time.Instant;

public record RecoveryOperation(
    String operationId,
    ProjectId projectId,
    OperationStage stage,
    int progress,
    String message,
    CheckpointId targetCheckpoint,
    SnapshotId protectiveSnapshot,
    Instant createdAt,
    Instant updatedAt,
    String resultJson
) {}